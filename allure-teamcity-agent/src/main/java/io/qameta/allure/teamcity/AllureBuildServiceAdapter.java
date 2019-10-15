package io.qameta.allure.teamcity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.teamcity.callables.AddExecutorInfo;
import io.qameta.allure.teamcity.utils.ZipUtils;
import jetbrains.buildServer.RunBuildException;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.agent.artifacts.ArtifactsWatcher;
import jetbrains.buildServer.agent.runner.BuildServiceAdapter;
import jetbrains.buildServer.agent.runner.ProgramCommandLine;
import jetbrains.buildServer.agent.runner.SimpleProgramCommandLine;
import jetbrains.buildServer.runner.JavaRunnerConstants;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.SystemUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.teamcity.rest.Build;
import org.jetbrains.teamcity.rest.BuildConfigurationId;
import org.jetbrains.teamcity.rest.BuildLocator;
import org.jetbrains.teamcity.rest.TeamCityInstanceFactory;

import kotlin.sequences.Sequence;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static io.qameta.allure.teamcity.AllureConstants.ALLURE_ARTIFACT_HISTORY_LOCATION;
import static io.qameta.allure.teamcity.AllureConstants.ALLURE_ARTIFACT_META_LOCATION;
import static io.qameta.allure.teamcity.AllureConstants.ALLURE_TOOL_NAME;
import static io.qameta.allure.teamcity.AllureConstants.PUBLISH_MODE;
import static io.qameta.allure.teamcity.AllureConstants.REPORT_PATH_PREFIX;
import static io.qameta.allure.teamcity.AllureConstants.RESULTS_DIRECTORY;
import static io.qameta.allure.teamcity.utils.ZipUtils.listEntries;
import static java.lang.String.format;

/**
 * @author Dmitry Baev charlie@yandex-team.ru
 * Date: 06.08.15
 */
class AllureBuildServiceAdapter extends BuildServiceAdapter {

    private static final String ARCHIVE_NAME = "allure-report.zip";

    private static final String ALLURE_EXEC_NAME = "allure";

    private static final int LAST_FINISHED_BUILDS_MAX = 10;

    /**
     * Can be used to notify agent artifacts publisher about new artifacts to be
     * published during the build.
     */
    private final ArtifactsWatcher artifactsWatcher;

    /**
     * The absolute path to agent working directory.
     */
    private String workingDirectory;

    /**
     * The absolute path to the directory to generate report into.
     */
    private Path reportDirectory;

    /**
     * The absolute path to the directory with Allure results.
     */
    private Path resultsDirectory;

    /**
     * The absolute path to the directory with Allure commandline tool.
     */
    private Path clientDirectory;

    private AllurePublishMode publishMode;

    /**
     * Creates an instance of adapter.
     */
    AllureBuildServiceAdapter(@NotNull final ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterInitialized() throws RunBuildException {
        workingDirectory = getCheckoutDirectory().getAbsolutePath();
        resultsDirectory = getWorkingDirectoryPath().resolve(getRunnerParameters().get(RESULTS_DIRECTORY));
        clientDirectory = getClientDirectory(Paths.get(getToolPath(ALLURE_TOOL_NAME)));
        reportDirectory = getWorkingDirectoryPath().resolve(getRunnerParameters().get(REPORT_PATH_PREFIX));
        publishMode = Optional.ofNullable(getRunnerParameters().get(PUBLISH_MODE)).map(AllurePublishMode::valueOf)
                .orElse(AllurePublishMode.ARCHIVE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeProcessStarted() throws RunBuildException {
        if (Files.notExists(resultsDirectory)) {
            throw new RunBuildException(format("The results directory <%s> doesn't exists.", resultsDirectory));
        }
        copyHistory();
        try {
            clearReport();
            addExecutorInfo();
        } catch (Exception e) {
            throw new RunBuildException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public ProgramCommandLine makeProgramCommandLine() throws RunBuildException {

        BuildProgressLogger buildLogger = getLogger();

        Map<String, String> envVariables = new HashMap<>(getRunnerContext()
                .getBuildParameters()
                .getEnvironmentVariables());
        Optional.ofNullable(getRunnerContext().getRunnerParameters().get(JavaRunnerConstants.TARGET_JDK_HOME))
                .ifPresent(javaHome -> envVariables.put("JAVA_HOME", javaHome));

        String programPath = getProgramPath();
        List<String> programArgs = getProgramArgs();

        buildLogger.message("Program environment variables: " + envVariables.toString());
        buildLogger.message("Program working directory: " + workingDirectory);
        buildLogger.message("Program path: " + programPath);
        buildLogger.message("Program args: " + programArgs.toString());

        return new SimpleProgramCommandLine(envVariables, workingDirectory, programPath, programArgs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterProcessSuccessfullyFinished() throws RunBuildException {
        try {
            publishAllureReport();
            publishAllureHistory();
            publishAllureSummary();
        } catch (IOException e) {
            throw new RunBuildException(e);
        }
    }

    private void publishAllureReport() throws IOException {
        if (publishMode.equals(AllurePublishMode.ARCHIVE)) {
            Path reportArchive = getAgentTempDirectoryPath().resolve(ARCHIVE_NAME);
            List<Path> reportFiles = Files.walk(reportDirectory)
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());
            ZipUtils.zip(reportArchive, reportDirectory.getParent(), reportFiles);
            artifactsWatcher.addNewArtifactsPath(reportArchive.toString());
        }
        if (publishMode.equals(AllurePublishMode.PLAIN)) {
            String reportDirectoryName = reportDirectory.toFile().getName();
            artifactsWatcher.addNewArtifactsPath(String.format("%s => %s",
                    reportDirectory.toString(),
                    reportDirectoryName));
        }
    }

    private void publishAllureHistory() throws IOException {
        Path historyArchive = getAgentTempDirectoryPath().resolve("history.zip");

        Path historyDirectory = reportDirectory.resolve("history");
        List<Path> historyFiles = Files.walk(historyDirectory)
                .filter(Files::isRegularFile)
                .collect(Collectors.toList());
        ZipUtils.zip(historyArchive, historyDirectory.getParent(), historyFiles);
        artifactsWatcher.addNewArtifactsPath(historyArchive.toString() + " => " + ALLURE_ARTIFACT_META_LOCATION);
    }

    private void publishAllureSummary() throws IOException {
        Path summaryOutputPath = getAgentTempDirectoryPath().resolve("summary.json");

        Path summaryWidgetPath = reportDirectory.resolve("widgets").resolve("summary.json");
        ObjectMapper mapper = new ObjectMapper();

        AllureReportSummary summary = mapper.readValue(summaryWidgetPath.toFile(), AllureReportSummary.class);
        summary.setUrl(getAllureReportUrl());

        String json = mapper.writeValueAsString(summary);
        Files.write(summaryOutputPath, json.getBytes());

        artifactsWatcher.addNewArtifactsPath(summaryOutputPath + "=>"
                + AllureConstants.ALLURE_ARTIFACT_META_LOCATION);
    }

    private void clearReport() throws IOException {
        if (Files.exists(reportDirectory)) {
            FileUtils.deleteDirectory(reportDirectory.toFile());
        }
    }

    /**
     * Write the history file to results directory.
     */
    private void copyHistory() {
        try {
            Iterator<Build> buildsIterator = getLastFinishedBuilds().iterator();

            while (buildsIterator.hasNext() && !historyExists()) {
                Build build = buildsIterator.next();
                copyHistoryFromBuild(build, ALLURE_ARTIFACT_HISTORY_LOCATION);
                copyHistoryFromBuild(build, ARCHIVE_NAME);
            }
        } catch (IOException e) {
            getLogger().message("Cannot copy history file. Reason: " + e.getMessage());
            getLogger().exception(e);
        }
    }

    private void copyHistoryFromBuild(Build build, String artifactDependencies) throws IOException {
        getLogger().message(format("Search allure history information in %s #%s ...",
                build.getBuildConfigurationId(), build.getBuildNumber()));

        if (historyExists()) return;

        Path artifactsZip = Files.createTempFile("artifact", String.valueOf(getBuild().getBuildId()));

        try {
            build.downloadArtifact(artifactDependencies, Files.newOutputStream(artifactsZip));
        } catch (Exception e) {
            getLogger().message("Cannot download artifact. Reason: " + e.getMessage());
            return;
        }

        getLogger().message("Coping allure history information ...");
        try (ZipFile archive = new ZipFile(artifactsZip.toString())) {
            copyHistoryToResultsPath(archive, resultsDirectory);
        }
    }

    private boolean historyExists() throws IOException {
        Path historyDirectory = resultsDirectory.resolve("history");
        Files.createDirectories(historyDirectory);

        return Files.list(historyDirectory).count() != 0;
    }

    private void copyHistoryToResultsPath(ZipFile archive, Path resultsDirectory) throws IOException {
        for (final ZipEntry historyEntry : listEntries(archive, "history")) {
            final String historyFile = historyEntry.getName();

            if (historyEntry.isDirectory()) {
                Files.createDirectories(resultsDirectory.resolve(historyFile));
            } else {
                try (InputStream entryStream = archive.getInputStream(historyEntry)) {
                    Files.copy(entryStream, resultsDirectory.resolve(historyFile));
                }
            }
        }
    }

    /**
     * Write the test executor info to results directory.
     */
    private void addExecutorInfo() throws IOException {
        String rootUrl = getTeamcityBaseUrl();
        String buildUrl = getBuildUrl();
        String buildNumber = getBuildNumber();
        String reportUrl = getAllureReportUrl();

        String buildName = format("%s / %s # %s",
                getBuild().getProjectName(), getBuild().getBuildTypeName(), buildNumber);
        new AddExecutorInfo(rootUrl, buildName, buildUrl, buildNumber, reportUrl)
                .invoke(resultsDirectory);
    }

    /**
     * Returns the sequence of finished builds for current build configuration
     */
    private Sequence<Build> getLastFinishedBuilds() {
        BuildConfigurationId buildConfigurationId = new BuildConfigurationId(getBuild().getBuildTypeExternalId());

        BuildLocator buildLocator = TeamCityInstanceFactory.httpAuth(getTeamcityBaseUrl(), getAuthUserId(), getAuthPassword())
                .builds()
                .fromConfiguration(buildConfigurationId)
                .includeFailed()
                .limitResults(LAST_FINISHED_BUILDS_MAX);

        String branch = getConfigParameters().get("teamcity.build.branch");
        if (Objects.nonNull(branch)) {
            buildLocator.withBranch(branch);
        }
        return buildLocator.all();
    }

    /**
     * Returns teamcity auth userId.
     */
    @NotNull
    private String getAuthUserId() {
        return getSystemProperties().get("teamcity.auth.userId");
    }

    /**
     * Returns teamcity auth password.
     */
    @NotNull
    private String getAuthPassword() {
        return getSystemProperties().get("teamcity.auth.password");
    }

    /**
     * Returns the build's artifacts url for current build.
     *
     * @see #getTeamcityBaseUrl()
     */
    @NotNull
    private String getAllureReportUrl() {
        String reportDirectoryName = reportDirectory.toFile().getName();
        String artifactPath = publishMode.equals(AllurePublishMode.ARCHIVE)
                ? String.format("%s!/%s/index.html", ARCHIVE_NAME, reportDirectoryName)
                : String.format("%s/index.html", reportDirectoryName);

        StringBuilder artifactUrl = new StringBuilder();
        artifactUrl.append(format("%s/repository/download/%s/%s/%s",
                getTeamcityBaseUrl(), getBuild().getBuildTypeExternalId(), getBuild().getBuildId(), artifactPath));

        String branch = getConfigParameters().get("teamcity.build.branch");
        if (Objects.nonNull(branch)) {
            artifactUrl.append(format("?branch=%s", branch));
        }
        return artifactUrl.toString();
    }

    private String getBuildNumber() {
        return getBuild().getBuildNumber();
    }

    /**
     * Returns the build url for current build.
     *
     * @see #getTeamcityBaseUrl()
     */
    @NotNull
    private String getBuildUrl() {
        return format(
                "%sviewLog.html?tab=buildResultsDiv&buildId=%s&buildTypeId=%s",
                getTeamcityBaseUrl(),
                getBuild().getBuildId(),
                getBuild().getBuildTypeExternalId()
        );
    }

    /**
     * Returns the base url of teamcity server.
     */
    @NotNull
    private String getTeamcityBaseUrl() {
        String baseUrl = getConfigParameters().get("teamcity.serverUrl");
        return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    private List<String> getProgramArgs() {
        List<String> list = new ArrayList<>();

        list.add("generate");
        list.add(resultsDirectory.toString());
        list.add("-o");
        list.add(reportDirectory.toString());

        return list;
    }

    private String getProgramPath() throws RunBuildException {

        BuildProgressLogger buildLogger = getLogger();
        String path = clientDirectory.toAbsolutePath().toString();
        String executableName = getExecutableName();

        String executableFile = path + File.separatorChar + "bin" + File.separatorChar + executableName;
        File file = new File(executableFile);

        if (!file.exists()) {
            throw new RunBuildException("Cannot find executable \'" + executableFile + "\'");
        }
        if (!file.setExecutable(true)) {
            buildLogger.message("Cannot set file: " + executableFile + " executable.");
        }
        return file.getAbsolutePath();

    }

    private static Path getClientDirectory(Path client) throws RunBuildException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(client, Files::isDirectory)) {
            for (Path path : stream) {
                if (path.getFileName().toString().startsWith("allure")) {
                    return path;
                }
            }
        } catch (IOException e) {
            throw new RunBuildException("Cannot find allure tool folder.", e);
        }
        return client;
    }


    @NotNull
    private static String getExecutableName() {
        return SystemUtils.IS_OS_WINDOWS ? ALLURE_EXEC_NAME + ".bat" : ALLURE_EXEC_NAME;
    }

    @NotNull
    private Path getWorkingDirectoryPath() {
        return Paths.get(workingDirectory);
    }

    private Path getAgentTempDirectoryPath() {
        return getAgentTempDirectory().toPath();
    }

}
