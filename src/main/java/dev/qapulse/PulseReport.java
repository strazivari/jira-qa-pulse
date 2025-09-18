package dev.qapulse;

import dev.qapulse.PulseData.ActionType;
import dev.qapulse.PulseData.Cause;
import dev.qapulse.PulseData.Diagnosis;
import dev.qapulse.PulseData.Issue;
import dev.qapulse.PulseData.ReportStatus;
import dev.qapulse.PulseData.Run;
import dev.qapulse.PulseData.StandAction;
import dev.qapulse.PulseData.Status;
import dev.qapulse.PulseData.Transition;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

final class PulseReport {
    private static final long ONE_DAY_SECONDS = Duration.ofDays(1).toSeconds();

    record Interval(Status status, Instant start, Instant end, long seconds, boolean current) {}
    record IssueStats(Issue issue, Status status, long currentWait, long totalWait, long totalTesting,
                      long returns, long readyVisits, List<Interval> timeline) {}
    record Queue(List<IssueStats> ready, long overOneDay, long returnsToDevelopment) {}

    record Failure(Run original, Diagnosis diagnosis, List<Run> retries, List<StandAction> linkedActions) {}
    record Impact(long restarts, long redeploys, long workSeconds) {}
    record Tester(String name, long failedReports, Impact impact) {}
    record Failures(long runs, List<Failure> failedReports, long qaFailures,
                    long retries, Impact qaImpact, List<Tester> testers) {}

    private PulseReport() {}

    static Queue queue(PulseData data) {
        var transitions = data.transitions().stream()
                .filter(transition -> !transition.at().isAfter(data.asOf()))
                .sorted(Comparator.comparing(Transition::at))
                .collect(Collectors.groupingBy(Transition::issueKey));

        var all = data.issues().stream()
                .filter(issue -> !issue.createdAt().isAfter(data.asOf()))
                .map(issue -> issueStats(issue, transitions.getOrDefault(issue.key(), List.of()), data.asOf()))
                .toList();

        var ready = all.stream().filter(issue -> issue.status() == Status.READY_FOR_QA)
                .sorted(Comparator.comparingLong(IssueStats::currentWait).reversed()
                        .thenComparing(stats -> stats.issue().key()))
                .toList();
        return new Queue(ready, ready.stream().filter(issue -> issue.currentWait() > ONE_DAY_SECONDS).count(),
                all.stream().mapToLong(IssueStats::returns).sum());
    }

    private static IssueStats issueStats(Issue issue, List<Transition> transitions, Instant asOf) {
        var timeline = new ArrayList<Interval>();
        var status = issue.initialStatus();
        var start = issue.createdAt();
        long returns = 0;
        for (var transition : transitions) {
            if (transition.to() == status) {
                continue;
            }
            timeline.add(interval(status, start, transition.at(), false));
            if (status == Status.IN_QA && transition.to() == Status.DEVELOPMENT) {
                returns++;
            }
            status = transition.to();
            start = transition.at();
        }

        var current = interval(status, start, asOf, true);
        timeline.add(current);
        long waiting = durationIn(timeline, Status.READY_FOR_QA);
        long testing = durationIn(timeline, Status.IN_QA);
        long visits = timeline.stream().filter(step -> step.status() == Status.READY_FOR_QA).count();
        long currentWait = status == Status.READY_FOR_QA ? current.seconds() : 0;
        return new IssueStats(issue, status, currentWait, waiting, testing, returns, visits, List.copyOf(timeline));
    }

    static Failures failures(PulseData data, int days) {
        Instant cutoff = days == 0 ? Instant.MIN : data.asOf().minus(Duration.ofDays(days));
        var visibleRuns = data.runs().stream().filter(run -> !run.startedAt().isAfter(data.asOf())).toList();
        var cohort = visibleRuns.stream().filter(run -> !run.startedAt().isBefore(cutoff)).toList();
        var diagnoses = data.diagnoses().stream().collect(Collectors.toMap(Diagnosis::runId, Function.identity()));
        var retriesByOriginal = visibleRuns.stream().filter(run -> run.retryOf() != null)
                .sorted(Comparator.comparing(Run::startedAt))
                .collect(Collectors.groupingBy(Run::retryOf));
        var actionsByCause = data.actions().stream()
                .filter(action -> action.causedByRunId() != null && !action.at().isAfter(data.asOf()))
                .sorted(Comparator.comparing(StandAction::at))
                .collect(Collectors.groupingBy(StandAction::causedByRunId));

        var failed = cohort.stream().filter(run -> run.reportStatus() == ReportStatus.FAILED)
                .sorted(Comparator.comparing(Run::startedAt).reversed().thenComparing(Run::id))
                .map(run -> new Failure(run, diagnoses.getOrDefault(run.id(), pending(run)),
                        retriesByOriginal.getOrDefault(run.id(), List.of()),
                        actionsByCause.getOrDefault(run.id(), List.of())))
                .toList();
        var byQa = failed.stream().filter(row -> row.diagnosis().cause() == Cause.QA_INPUT).toList();
        var testers = byQa.stream()
                .collect(Collectors.groupingBy(row -> row.diagnosis().actor(), TreeMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(person -> new Tester(person.getKey(), person.getValue().size(), impact(person.getValue())))
                .toList();

        long retries = failed.stream().mapToLong(row -> row.retries().size()).sum();
        return new Failures(cohort.size(), failed, byQa.size(), retries, impact(byQa), testers);
    }

    private static Diagnosis pending(Run run) {
        return new Diagnosis(run.id(), Cause.UNDER_REVIEW, null, "Причина уточняется", null);
    }

    private static Impact impact(List<Failure> failures) {
        long restarts = 0;
        long redeploys = 0;
        long seconds = 0;
        for (var failure : failures) {
            for (var action : failure.linkedActions()) {
                if (action.type() == ActionType.SERVICE_RESTART) {
                    restarts++;
                } else {
                    redeploys++;
                }
                seconds += action.seconds();
            }
        }
        return new Impact(restarts, redeploys, seconds);
    }

    private static Interval interval(Status status, Instant start, Instant end, boolean current) {
        return new Interval(status, start, end, Duration.between(start, end).toSeconds(), current);
    }

    private static long durationIn(List<Interval> intervals, Status status) {
        return intervals.stream().filter(interval -> interval.status() == status).mapToLong(Interval::seconds).sum();
    }
}
