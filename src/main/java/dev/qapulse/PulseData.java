package dev.qapulse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

record PulseData(String project, Instant asOf, List<Issue> issues, List<Transition> transitions,
                 List<Run> runs, List<Diagnosis> diagnoses, List<StandAction> actions) {

    enum Status { BACKLOG, DEVELOPMENT, READY_FOR_QA, IN_QA, DONE }
    enum ReportStatus { GENERATED, FAILED }
    enum Cause { QA_INPUT, ENVIRONMENT, REPORT_CODE, CI, UNDER_REVIEW }
    enum ActionType { SERVICE_RESTART, REDEPLOY }

    record Issue(String key, String title, Instant createdAt, Status initialStatus) {}
    record Transition(String issueKey, Instant at, Status to) {}
    record Run(String id, String issueKey, Instant startedAt, String startedBy,
               String retryOf, ReportStatus reportStatus) {}
    record Diagnosis(String runId, Cause cause, String actor, String detail, String evidence) {}
    record StandAction(String id, String runId, String causedByRunId, Instant at,
                       ActionType type, long seconds, String evidence) {}

    static PulseData load(Path directory) throws IOException {
        var config = new Properties();
        try (var input = Files.newBufferedReader(directory.resolve("snapshot.properties"), StandardCharsets.UTF_8)) {
            config.load(input);
        }

        var data = new PulseData(
                required(config.getProperty("project")), Instant.parse(required(config.getProperty("asOf"))),
                read(directory, "issues.tsv", "key\ttitle\tcreatedAt\tinitialStatus", row ->
                        new Issue(required(row[0]), required(row[1]), Instant.parse(row[2]), Status.valueOf(row[3]))),
                read(directory, "transitions.tsv", "issueKey\tat\tto", row ->
                        new Transition(required(row[0]), Instant.parse(row[1]), Status.valueOf(row[2]))),
                read(directory, "runs.tsv", "id\tissueKey\tstartedAt\tstartedBy\tretryOf\treportStatus", row ->
                        new Run(required(row[0]), required(row[1]), Instant.parse(row[2]), required(row[3]),
                                optional(row[4]), ReportStatus.valueOf(row[5]))),
                read(directory, "diagnoses.tsv", "runId\tcause\tactor\tdetail\tevidence", row ->
                        new Diagnosis(required(row[0]), Cause.valueOf(row[1]), optional(row[2]),
                                required(row[3]), optional(row[4]))),
                read(directory, "actions.tsv", "id\trunId\tcausedByRunId\tat\ttype\tseconds\tevidence", row ->
                        new StandAction(required(row[0]), required(row[1]), optional(row[2]), Instant.parse(row[3]),
                                ActionType.valueOf(row[4]), Long.parseLong(row[5]), required(row[6]))));
        data.validate();
        return data;
    }

    private void validate() {
        var issueByKey = index(issues, Issue::key, "issue key");
        var runById = index(runs, Run::id, "run ID");
        index(diagnoses, Diagnosis::runId, "diagnosis run ID");
        index(actions, StandAction::id, "stand action ID");

        for (var transition : transitions) {
            var issue = issueByKey.get(transition.issueKey());
            check(issue != null && !transition.at().isBefore(issue.createdAt()),
                    "Invalid transition for " + transition.issueKey());
        }
        for (var run : runs) {
            var issue = issueByKey.get(run.issueKey());
            check(issue != null && !run.startedAt().isBefore(issue.createdAt()), "Invalid issue for run " + run.id());
            if (run.retryOf() != null) {
                var previous = runById.get(run.retryOf());
                check(previous != null && previous.issueKey().equals(run.issueKey())
                                && previous.startedAt().isBefore(run.startedAt()),
                        "Invalid retry relationship for run " + run.id());
            }
        }
        for (var diagnosis : diagnoses) {
            var run = runById.get(diagnosis.runId());
            check(run != null && run.reportStatus() == ReportStatus.FAILED,
                    "Diagnosis must reference a failed report: " + diagnosis.runId());
            if (diagnosis.cause() != Cause.UNDER_REVIEW) {
                check(diagnosis.evidence() != null, "Confirmed cause needs evidence: " + diagnosis.runId());
            }
            if (diagnosis.cause() == Cause.QA_INPUT) {
                check(diagnosis.actor() != null, "QA input cause needs an identified actor: " + diagnosis.runId());
            }
        }
        for (var action : actions) {
            var run = runById.get(action.runId());
            check(run != null && !action.at().isBefore(run.startedAt()) && action.seconds() >= 0,
                    "Invalid stand action: " + action.id());
            if (action.causedByRunId() != null) {
                var cause = runById.get(action.causedByRunId());
                check(cause != null && cause.reportStatus() == ReportStatus.FAILED
                                && followsRetryChain(run, cause.id(), runById),
                        "Stand action cause must be a failed ancestor of the retry: " + action.id());
            }
        }
    }

    private static boolean followsRetryChain(Run run, String causeId, Map<String, Run> runs) {
        for (String previous = run.retryOf(); previous != null; previous = runs.get(previous).retryOf()) {
            if (previous.equals(causeId)) {
                return true;
            }
        }
        return false;
    }

    private static <T> Map<String, T> index(List<T> values, Function<T, String> id, String label) {
        var index = new LinkedHashMap<String, T>();
        for (var value : values) {
            String key = id.apply(value);
            check(index.putIfAbsent(key, value) == null, "Duplicate " + label + ": " + key);
        }
        return index;
    }

    private static <T> List<T> read(Path directory, String file, String header, Function<String[], T> parse)
            throws IOException {
        var lines = Files.readAllLines(directory.resolve(file), StandardCharsets.UTF_8);
        check(!lines.isEmpty() && header.equals(lines.get(0).replaceFirst("^\uFEFF", "")), "Invalid header: " + file);
        int columns = header.split("\t", -1).length;
        var rows = new ArrayList<T>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            try {
                String[] values = lines.get(i).split("\t", -1);
                check(values.length == columns, "Expected " + columns + " columns");
                rows.add(parse.apply(values));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + " " + error.getMessage(), error);
            }
        }
        return List.copyOf(rows);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String required(String value) {
        String result = optional(value);
        check(result != null, "Required field is empty");
        return result;
    }

    private static void check(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
