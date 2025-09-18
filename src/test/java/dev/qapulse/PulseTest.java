package dev.qapulse;

import dev.qapulse.PulseData.Cause;
import dev.qapulse.PulseData.Issue;
import dev.qapulse.PulseData.Status;
import dev.qapulse.PulseData.Transition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class PulseTest {
    private PulseTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        var data = PulseData.load(root.resolve("data"));

        var queue = PulseReport.queue(data);
        equal(8, queue.ready().size(), "Queue size");
        equal(3L, queue.overOneDay(), "Wait over one day");
        equal(2L, queue.returnsToDevelopment(), "Returns in issue histories");
        equal("SHOP-128", queue.ready().get(0).issue().key(), "Oldest issue first");
        var issue = queue.ready().stream().filter(row -> row.issue().key().equals("SHOP-142")).findFirst().orElseThrow();
        equal(6L * 3600, issue.currentWait(), "Current wait");
        equal(24L * 3600, issue.totalWait(), "Total wait across two visits");
        equal(3L * 3600, issue.totalTesting(), "Time in QA");
        equal(2L, issue.readyVisits(), "QA visits");
        equal(1L, issue.returns(), "Issue returns");
        System.out.println("PASS queue and two QA visits");

        checkQueueBoundary();
        System.out.println("PASS exact queue threshold and future transitions");

        var report = PulseReport.failures(data, 7);
        equal(13L, report.runs(), "Runs");
        equal(4, report.failedReports().size(), "Failed reports");
        equal(2L, report.qaFailures(), "Confirmed QA causes");
        equal(4L, report.retries(), "Direct retries");
        equal(new PulseReport.Impact(0, 1, 480), report.qaImpact(), "Confirmed QA environment impact");
        System.out.println("PASS failure summary and distinct counts");

        var profile = find(report, "315");
        equal("Алексей", profile.original().startedBy(), "Original initiator");
        equal("CI", profile.retries().get(0).startedBy(), "Retry initiator");
        equal("D11", profile.linkedActions().get(0).id(), "Causal deploy log");
        var wrongPath = find(report, "317");
        equal("Ирина", wrongPath.diagnosis().actor(), "Confirmed QA actor");
        equal("Алексей", wrongPath.retries().get(0).startedBy(), "A colleague retried the failed run");
        equal(0, wrongPath.linkedActions().size(), "A retry can run without a stand action");
        equal(2, report.testers().size(), "Two confirmed QA actors");
        equal(1L, report.testers().stream().filter(person -> person.name().equals("Алексей"))
                .findFirst().orElseThrow().failedReports(), "Retry attribution does not add a failure");
        System.out.println("PASS original cause, retry operator and named stats");

        equal(Cause.ENVIRONMENT, find(report, "319").diagnosis().cause(), "Environment cause");
        equal("R04", find(report, "319").linkedActions().get(0).id(), "Environment restart retained in drilldown");
        equal(Cause.UNDER_REVIEW, find(report, "321").diagnosis().cause(), "Missing diagnosis stays under review");
        equal(0, find(report, "321").linkedActions().size(), "Scheduled stand refresh excluded");
        equal(5, PulseReport.failures(data, 30).failedReports().size(), "30 days");
        equal(6, PulseReport.failures(data, 0).failedReports().size(), "All history");
        System.out.println("PASS unlinked actions, pending diagnosis and report periods");

        checkInvalidCausalLink(root.resolve("data"));
        System.out.println("PASS rejects an unrelated failed run as cause");

        checkHttp(root);
        System.out.println("PASS UI resources and API over HTTP");
    }

    private static void checkQueueBoundary() {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        var issue = new Issue("SHOP-1", "Boundary", now.minus(Duration.ofDays(1)), Status.READY_FOR_QA);
        var data = new PulseData("SHOP", now, List.of(issue),
                List.of(new Transition(issue.key(), now.plusSeconds(1), Status.IN_QA)),
                List.of(), List.of(), List.of());
        var queue = PulseReport.queue(data);
        equal(1, queue.ready().size(), "Future transitions do not change the snapshot");
        equal(0L, queue.overOneDay(), "Exactly 24h is not greater than 24h");
    }

    private static void checkInvalidCausalLink(Path source) throws Exception {
        var temp = Files.createTempDirectory("qa-pulse-invalid-");
        var filenames = List.of("snapshot.properties", "issues.tsv", "transitions.tsv",
                "runs.tsv", "diagnoses.tsv", "actions.tsv");
        try {
            for (String name : filenames) {
                Files.copy(source.resolve(name), temp.resolve(name));
            }
            var actions = temp.resolve("actions.tsv");
            var edited = Files.readString(actions).replace("D11\t316\t315\t", "D11\t316\t319\t");
            Files.writeString(actions, edited);
            try {
                PulseData.load(temp);
                throw new AssertionError("Unrelated action cause accepted");
            } catch (IllegalArgumentException error) {
                contains(error.getMessage(), "failed ancestor");
            }
        } finally {
            for (String name : filenames) {
                Files.deleteIfExists(temp.resolve(name));
            }
            Files.delete(temp);
        }
    }

    private static void checkHttp(Path root) throws Exception {
        var server = PulseServer.start(root, 0);
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            contains(get(client, url + "/", 200), "Jira QA Pulse");
            contains(get(client, url + "/app.js", 200), "renderFailureReport");
            contains(get(client, url + "/styles.css", 200), ".metrics");
            contains(get(client, url + "/api/queue", 200), "\"currentWait\":21600,\"totalWait\":86400");
            contains(get(client, url + "/api/failures?days=7", 200),
                    "\"qaImpact\":{\"restarts\":0,\"redeploys\":1,\"workSeconds\":480}");
            contains(get(client, url + "/api/failures?days=all", 200), "\"failed\":6");
            contains(get(client, url + "/api/failures?days=99", 400), "days must be");
            get(client, url + "/missing", 404);
        } finally {
            server.stop(0);
        }
    }

    private static PulseReport.Failure find(PulseReport.Failures report, String id) {
        return report.failedReports().stream().filter(row -> row.original().id().equals(id))
                .findFirst().orElseThrow();
    }

    private static String get(HttpClient client, String url, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        equal(status, response.statusCode(), url);
        return response.body();
    }

    private static void equal(Object expected, Object actual, String field) {
        if (!expected.equals(actual)) {
            throw new AssertionError(field + ": expected " + expected + ", got " + actual);
        }
    }

    private static void contains(String text, String expected) {
        if (!text.contains(expected)) {
            throw new AssertionError("Expected " + expected + " in " + text);
        }
    }
}
