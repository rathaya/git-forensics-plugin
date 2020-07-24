package io.jenkins.plugins.forensics.git.reference;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import edu.hm.hafner.util.FilteredLog;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import hudson.model.Run;
import hudson.scm.SCM;
import jenkins.model.RunAction2;

/**
 * Stores all commits for a given build and provides a link to the latest commit. For each {@link SCM} repository
 * a unique {@link GitCommitsRecord} instance will be used.
 *
 * @author Arne Schöntag
 */
@SuppressFBWarnings(value = "SE", justification = "transient field owner ist restored using a Jenkins callback")
public class GitCommitsRecord implements RunAction2, Serializable {
    private static final long serialVersionUID = 8994811233847179343L;

    private transient Run<?, ?> owner;

    /**
     * Key of the repository. The {@link GitCheckoutListener} ensures that a single action will be created for each
     * repository.
     */
    private final String scmKey;
    private final String latestCommit;
    private final RecordingType recordingType;
    private final List<String> commits;
    private final List<String> errorMessages;
    private final List<String> infoMessages;

    /** Determines if this record is the starting point or an incremental record that is based on the previous record. */
    enum RecordingType {
        START,
        INCREMENTAL
    }

    /**
     * Creates a new {@link GitCommitsRecord} instance with the specified list of new commits.
     *
     * @param owner
     *         the current build as owner of the Git commits
     * @param scmKey
     *         the ID of the SCM repository
     * @param logger
     *         the logger
     * @param latestCommit
     *         the latest commit (either the head of the new commits or the latest commit from the previous build)
     * @param commits
     *         the new commits in this build (since the previous build)
     * @param recordingType the recording type that indicates if the number of commits is
     */
    GitCommitsRecord(final Run<?, ?> owner, final String scmKey,
            final FilteredLog logger, final String latestCommit, final List<String> commits,
            final RecordingType recordingType) {
        super();

        this.owner = owner;
        this.scmKey = scmKey;
        this.infoMessages = new ArrayList<>(logger.getInfoMessages());
        this.errorMessages = new ArrayList<>(logger.getErrorMessages());
        this.commits = new ArrayList<>(commits);
        this.latestCommit = latestCommit;
        this.recordingType = recordingType;
    }

    /**
     * Creates a new {@link GitCommitsRecord} instance with the specified list of new commits.
     *
     * @param owner
     *         the current build as owner of the Git commits
     * @param scmKey
     *         the ID of the SCM repository
     * @param logger
     *         the logger
     * @param latestCommit
     *         the latest commit (either the head of the new commits or the latest commit from the previous build)
     * @param commits
     *         the new commits in this build (since the previous build)
     */
    GitCommitsRecord(final Run<?, ?> owner, final String scmKey,
            final FilteredLog logger, final String latestCommit, final List<String> commits) {
        this(owner, scmKey, logger, latestCommit, commits, RecordingType.INCREMENTAL);
    }

    /**
     * Creates a new {@link GitCommitsRecord} instance with an empty list of commits.
     *
     * @param owner
     *         the current build as owner of the Git commits
     * @param scmKey
     *         the ID of the SCM repository
     * @param logger
     *         the logger
     * @param latestCommit
     *         the latest commit of the previous build
     */
    GitCommitsRecord(final Run<?, ?> owner, final String scmKey,
            final FilteredLog logger, final String latestCommit) {
        this(owner, scmKey, logger, latestCommit, Collections.emptyList());
    }

    public Run<?, ?> getOwner() {
        return owner;
    }

    public boolean isFirstBuild() {
        return recordingType == RecordingType.START;
    }

    public String getScmKey() {
        return scmKey;
    }

    public String getLatestCommit() {
        return latestCommit;
    }

    public List<String> getErrorMessages() {
        return errorMessages;
    }

    public List<String> getInfoMessages() {
        return infoMessages;
    }

    /**
     * Returns the number of new commits.
     *
     * @return the number of new commits
     */
    public int size() {
        return commits.size();
    }

    public boolean isNotEmpty() {
        return !commits.isEmpty();
    }

    public boolean isEmpty() {
        return commits.isEmpty();
    }

    public List<String> getCommits() {
        return commits;
    }

    @Override
    public void onAttached(final Run<?, ?> run) {
        this.owner = run;
    }

    @Override
    public void onLoad(final Run<?, ?> run) {
        onAttached(run);
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return Messages.Action_DisplayName();
    }

    @Override
    public String getUrlName() {
        return null;
    }

    @Override
    public String toString() {
        return String.format("Commits in '%s': %d (latest: %s)", owner, size(), getLatestCommit());
    }

    /**
     * Tries to find the reference point of the GitCommit of another build.
     *
     * @param reference
     *         the GitCommit of the other build
     * @param maxLogs
     *         maximal amount of commits looked at.
     *
     * @return the build Id of the reference build or Optional.empty() if none found.
     */
    public Optional<String> getReferencePoint(final GitCommitsRecord reference,
            final int maxLogs, final boolean skipUnknownCommits) {
        List<String> branchCommits = new ArrayList<>(this.getCommits());
        List<String> masterCommits = new ArrayList<>(reference.getCommits());

        Optional<String> referencePoint;

        // Fill branch commit list
        Run<?, ?> tmp = owner;
        while (branchCommits.size() < maxLogs && tmp != null) {
            GitCommitsRecord gitCommit = getGitCommitForRepository(tmp);
            if (gitCommit == null) {
                // Skip build if it has no GitCommit Action.
                tmp = tmp.getPreviousBuild();
                continue;
            }
            branchCommits.addAll(gitCommit.getCommits());
            tmp = tmp.getPreviousBuild();
        }

        // Fill master commit list and check for intersection point
        tmp = reference.owner;
        while (masterCommits.size() < maxLogs && tmp != null) {
            GitCommitsRecord gitCommit = getGitCommitForRepository(tmp);
            if (gitCommit == null) {
                // Skip build if it has no GitCommit Action.
                tmp = tmp.getPreviousBuild();
                continue;
            }
            List<String> commits = gitCommit.getCommits();
            if (skipUnknownCommits && !branchCommits.containsAll(commits)) {
                // Skip build if it has unknown commits to current branch.
                tmp = tmp.getPreviousBuild();
                continue;
            }
            masterCommits.addAll(commits);
            referencePoint = branchCommits.stream().filter(masterCommits::contains).findFirst();
            // If an intersection is found the buildId in Jenkins will be saved
            if (referencePoint.isPresent()) {
                return Optional.of(tmp.getExternalizableId());
            }
            tmp = tmp.getPreviousBuild();
        }
        return Optional.empty();
    }

    /**
     * If multiple Repositorys are in a build this GitCommit will only look a the ones with the same repositoryId.
     *
     * @param run
     *         the bulid to get the Actions from
     *
     * @return the correct GitCommit if present. Or else null.
     */
    private GitCommitsRecord getGitCommitForRepository(final Run<?, ?> run) {
        List<GitCommitsRecord> list = run.getActions(GitCommitsRecord.class);
        return list.stream().filter(gc -> this.getScmKey().equals(gc.getScmKey())).findFirst().orElse(null);
    }
}
