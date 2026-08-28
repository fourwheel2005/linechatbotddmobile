package com.example.linechatbotddmobile.service.line;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Repairs legacy duplicate states before the application becomes ready, then installs the database
 * uniqueness guard. Corrupted conversations are paused for admin handover instead of guessing which
 * concurrent state should continue talking to the customer.
 */
@Slf4j
@Component
public class UserStateIntegrityInitializer implements SmartInitializingSingleton {

    private static final String BALLOON_SERVICE = "ผ่อนบอลลูน";
    private static final String ADMIN_MODE = "ADMIN_MODE";
    private static final String DEFAULT_RESUME_STATE = "STEP_1_INFO";
    private static final String UNIQUE_INDEX_NAME = "uk_user_states_line_user_id";
    private static final Map<String, Integer> STATE_ORDER = buildStateOrder();

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public UserStateIntegrityInitializer(JdbcTemplate jdbcTemplate,
                                         PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void afterSingletonsInstantiated() {
        repairAndConstrain();
    }

    void repairAndConstrain() {
        transactionTemplate.executeWithoutResult(status -> {
            lockTableForRepair();
            int removedRows = repairDuplicates();
            createUniqueIndex();
            dropLegacyNonUniqueIndex();
            if (removedRows > 0) {
                log.warn("Repaired {} duplicate user-state rows; affected conversations were paused in ADMIN_MODE",
                        removedRows);
            }
        });
    }

    private int repairDuplicates() {
        List<String> duplicatedUsers = jdbcTemplate.queryForList("""
                SELECT line_user_id
                FROM user_states
                WHERE line_user_id IS NOT NULL
                GROUP BY line_user_id
                HAVING COUNT(*) > 1
                """, String.class);

        int removedRows = 0;
        for (String userId : duplicatedUsers) {
            removedRows += repairUser(userId);
        }
        return removedRows;
    }

    private int repairUser(String userId) {
        List<StateRow> rows = jdbcTemplate.query("""
                SELECT id, service_name, current_state, previous_state, capacity,
                       last_user_message, device_model, full_name, province
                FROM user_states
                WHERE line_user_id = ?
                ORDER BY id DESC
                """, this::mapStateRow, userId);
        if (rows.size() < 2) {
            return 0;
        }

        StateRow survivor = rows.get(0);
        updateSurvivor(survivor.id(), rows);
        return jdbcTemplate.update(
                "DELETE FROM user_states WHERE line_user_id = ? AND id <> ?",
                userId,
                survivor.id());
    }

    private void updateSurvivor(long survivorId, List<StateRow> rows) {
        String resumeState = selectResumeState(rows);
        String serviceName = newestText(rows, StateRow::serviceName);
        if (resumeState.startsWith("STEP_")) {
            serviceName = BALLOON_SERVICE;
        }

        jdbcTemplate.update("""
                UPDATE user_states
                SET service_name = ?, current_state = ?, previous_state = ?,
                    capacity = ?, last_user_message = ?, device_model = ?,
                    full_name = ?, province = ?, retry_count = 0,
                    follow_up_reminder_started_at = NULL,
                    follow_up_reminder_sent = FALSE
                WHERE id = ?
                """,
                serviceName, ADMIN_MODE, resumeState,
                newestText(rows, StateRow::capacity),
                newestText(rows, StateRow::lastUserMessage),
                newestText(rows, StateRow::deviceModel),
                newestText(rows, StateRow::fullName),
                newestText(rows, StateRow::province),
                survivorId);
    }

    private String selectResumeState(List<StateRow> rows) {
        return rows.stream()
                .flatMap(row -> List.of(
                        new StateCandidate(row.previousState(), row.id()),
                        new StateCandidate(row.currentState(), row.id())).stream())
                .filter(candidate -> isStepState(candidate.state()))
                .max(Comparator.comparingInt((StateCandidate value) -> stateRank(value.state()))
                        .thenComparingLong(StateCandidate::rowId))
                .map(StateCandidate::state)
                .orElse(DEFAULT_RESUME_STATE);
    }

    private String newestText(List<StateRow> rows, Function<StateRow, String> getter) {
        return rows.stream()
                .map(getter)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private StateRow mapStateRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StateRow(
                resultSet.getLong("id"),
                resultSet.getString("service_name"),
                resultSet.getString("current_state"),
                resultSet.getString("previous_state"),
                resultSet.getString("capacity"),
                resultSet.getString("last_user_message"),
                resultSet.getString("device_model"),
                resultSet.getString("full_name"),
                resultSet.getString("province"));
    }

    private void lockTableForRepair() {
        if (isPostgreSql()) {
            jdbcTemplate.execute("LOCK TABLE user_states IN ACCESS EXCLUSIVE MODE");
        }
    }

    private boolean isPostgreSql() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot inspect database before UserState integrity repair", exception);
        }
    }

    private void createUniqueIndex() {
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + UNIQUE_INDEX_NAME
                + " ON user_states (line_user_id)");
    }

    private void dropLegacyNonUniqueIndex() {
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_user_state_line_user_id");
    }

    private static boolean isStepState(String state) {
        return state != null && state.startsWith("STEP_");
    }

    private static int stateRank(String state) {
        return STATE_ORDER.getOrDefault(state, 0);
    }

    private static Map<String, Integer> buildStateOrder() {
        Map<String, Integer> order = new LinkedHashMap<>();
        List<String> states = List.of(
                "STEP_1_INFO", "STEP_2_CAPACITY", "STEP_3_PROVINCE", "STEP_4_AGE",
                "STEP_5_REPAIR", "STEP_6_FACEID", "STEP_7_INSTALLMENT",
                "STEP_8_DEVICE_PHOTOS", "STEP_9_SETTINGS_PHOTO", "STEP_9_APPROVED_PHOTO",
                "STEP_10_NAME", "STEP_11_SUBMIT_DATA", "STEP_5_PRICING",
                "STEP_6_MONTH_SELECTION");
        for (int index = 0; index < states.size(); index++) {
            order.put(states.get(index), index + 1);
        }
        return Map.copyOf(order);
    }

    private record StateCandidate(String state, long rowId) {
    }

    private record StateRow(
            long id,
            String serviceName,
            String currentState,
            String previousState,
            String capacity,
            String lastUserMessage,
            String deviceModel,
            String fullName,
            String province) {
    }
}
