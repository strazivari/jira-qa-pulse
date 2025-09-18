package dev.qapulse;

import dev.qapulse.PulseData.Run;
import dev.qapulse.PulseData.StandAction;
import dev.qapulse.PulseReport.Failure;
import dev.qapulse.PulseReport.Impact;
import dev.qapulse.PulseReport.Interval;
import dev.qapulse.PulseReport.IssueStats;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

final class ApiPayload {
    private ApiPayload() {}

    static String queue(PulseData data) {
        var queue = PulseReport.queue(data);
        return json(object("project", data.project(), "asOf", data.asOf().toString(),
                "ready", queue.ready().size(), "overOneDay", queue.overOneDay(),
                "returnsToDevelopment", queue.returnsToDevelopment(),
                "issues", queue.ready().stream().map(ApiPayload::issue).toList()));
    }

    static String failures(PulseData data, int days) {
        var report = PulseReport.failures(data, days);
        return json(object("asOf", data.asOf().toString(),
                "days", days, "runs", report.runs(), "failed", report.failedReports().size(),
                "qaFailures", report.qaFailures(), "retries", report.retries(), "qaImpact", impact(report.qaImpact()),
                "failures", report.failedReports().stream().map(ApiPayload::failure).toList(),
                "testers", report.testers().stream().map(tester -> object("name", tester.name(),
                        "failedReports", tester.failedReports(), "impact", impact(tester.impact()))).toList()));
    }

    private static Map<String, Object> issue(IssueStats stats) {
        return object("key", stats.issue().key(), "title", stats.issue().title(), "status", stats.status().name(),
                "currentWait", stats.currentWait(), "totalWait", stats.totalWait(), "totalTesting", stats.totalTesting(),
                "returns", stats.returns(), "readyVisits", stats.readyVisits(),
                "timeline", stats.timeline().stream().map(ApiPayload::interval).toList());
    }

    private static Map<String, Object> interval(Interval step) {
        return object("status", step.status().name(), "start", step.start().toString(), "end", step.end().toString(),
                "seconds", step.seconds(), "current", step.current());
    }

    private static Map<String, Object> failure(Failure row) {
        var cause = row.diagnosis();
        return object("run", run(row.original()), "cause", cause.cause().name(),
                "actor", cause.cause() == PulseData.Cause.QA_INPUT ? cause.actor() : null,
                "detail", cause.detail(), "evidence", cause.evidence(),
                "retries", row.retries().stream().map(ApiPayload::run).toList(),
                "actions", row.linkedActions().stream().map(ApiPayload::action).toList());
    }

    private static Map<String, Object> run(Run run) {
        return object("id", run.id(), "issueKey", run.issueKey(), "startedAt", run.startedAt().toString(),
                "startedBy", run.startedBy(), "reportStatus", run.reportStatus().name());
    }

    private static Map<String, Object> action(StandAction action) {
        return object("id", action.id(), "runId", action.runId(), "at", action.at().toString(),
                "type", action.type().name(), "seconds", action.seconds(), "evidence", action.evidence());
    }

    private static Map<String, Object> impact(Impact impact) {
        return object("restarts", impact.restarts(), "redeploys", impact.redeploys(), "workSeconds", impact.workSeconds());
    }

    private static Map<String, Object> object(Object... entries) {
        var values = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put((String) entries[i], entries[i + 1]);
        }
        return values;
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quote(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> fields) {
            var result = new StringJoiner(",", "{", "}");
            fields.forEach((key, child) -> result.add(quote((String) key) + ":" + json(child)));
            return result.toString();
        }
        if (value instanceof List<?> items) {
            var result = new StringJoiner(",", "[", "]");
            items.forEach(item -> result.add(json(item)));
            return result.toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    private static String quote(String text) {
        var result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '"' || character == '\\') {
                result.append('\\').append(character);
            } else if (character < ' ') {
                result.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.append('"').toString();
    }
}
