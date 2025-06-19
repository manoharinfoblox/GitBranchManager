package com.gitmanager.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GitService {

    private SseEmitter progressEmitter;

    public void setProgressEmitter(SseEmitter emitter) {
        this.progressEmitter = emitter;
    }

    public MergeResult mergeBranches(String sourceBranch, String targetBranch, String repositoryUrl, String token) throws GitAPIException, IOException {
        try (Repository repository = openRepository(repositoryUrl, token); Git git = new Git(repository)) {
            // Fetch latest changes
            git.fetch()
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token))
                .call();
            
            // Checkout target branch
            git.checkout().setName(targetBranch).setCreateBranch(true)
                .setStartPoint("origin/" + targetBranch).call();
            
            // Update target branch
            git.pull()
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token))
                .call();
            
            // Merge source branch into target branch
            return git.merge()
                .include(repository.resolve("origin/" + sourceBranch))
                .setCommit(true)
                .setMessage("Merge " + sourceBranch + " into " + targetBranch)
                .call();
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(GitService.class);

    public String getDiff(String sourceBranch, String targetBranch, String repositoryUrl, String token) throws GitAPIException, IOException {
        logger.info("Getting diff between {} and {} for repo {}", sourceBranch, targetBranch, repositoryUrl);
        try (Repository repository = openRepository(repositoryUrl, token); Git git = new Git(repository)) {
            logger.debug("Repository opened at: {}", repository.getDirectory());
            
            // Fetch latest changes
            git.fetch()
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token))
                .call();
            
            // Safely check out branches by first deleting them if they exist
            safeCheckoutBranch(git, sourceBranch);
            safeCheckoutBranch(git, targetBranch);

            // Get tree iterators for both branches
            ObjectReader reader = repository.newObjectReader();
            CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
            CanonicalTreeParser newTreeParser = new CanonicalTreeParser();
            
            oldTreeParser.reset(reader, repository.resolve(sourceBranch + "^{tree}"));
            newTreeParser.reset(reader, repository.resolve(targetBranch + "^{tree}"));

            // Get the diff
            List<DiffEntry> diffs = git.diff()
                    .setOldTree(oldTreeParser)
                    .setNewTree(newTreeParser)
                    .call();

            logger.info("Found {} differences", diffs.size());
            
            // Format the diff output using our helper method
            return formatDiffOutput(diffs, repository);
        } catch (Exception e) {
            logger.error("Error getting diff: {}", e.getMessage(), e);
            throw new IOException("Failed to get diff: " + e.getMessage(), e);
        }
    }

    public Set<String> getConflicts(String repositoryUrl, String token) throws IOException {
        try (Repository repository = openRepository(repositoryUrl, token); Git git = new Git(repository)) {
            // Fetch latest changes
            git.fetch()
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token))
                .call();
            
            return git.status().call().getConflicting();
        } catch (GitAPIException e) {
            throw new RuntimeException("Failed to get conflicts: " + e.getMessage(), e);
        }
    }

    private Repository openRepository(String repositoryUrl, String token) throws IOException {
        if (repositoryUrl == null || token == null) {
            throw new IllegalArgumentException("Repository URL and token must not be null");
        }

        // Create a unique directory for this repository based on its URL
        String repoName = repositoryUrl.substring(repositoryUrl.lastIndexOf('/') + 1);
        if (repoName.endsWith(".git")) {
            repoName = repoName.substring(0, repoName.length() - 4);
        }
        File gitDir = new File(System.getProperty("java.io.tmpdir"), "git-manager/" + repoName + "/.git");
        
        if (!gitDir.exists()) {
            try {
                gitDir.getParentFile().mkdirs();
                Git.cloneRepository()
                    .setURI(repositoryUrl)
                    .setDirectory(gitDir.getParentFile())
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token))
                    .setProgressMonitor(new GitProgressMonitor(progressEmitter))
                    .call();
            } catch (GitAPIException e) {
                throw new IOException("Failed to clone repository: " + e.getMessage(), e);
            }
        }

        try {
            return new FileRepositoryBuilder()
                    .setGitDir(gitDir)
                    .readEnvironment()
                    .build();
        } catch (IOException e) {
            throw new IOException("Failed to open repository: " + e.getMessage(), e);
        }
    }

    private static class GitProgressMonitor implements org.eclipse.jgit.lib.ProgressMonitor {
        private final SseEmitter emitter;
        private int totalWork;
        private int completedWork;
        private String taskTitle;

        public GitProgressMonitor(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void start(int totalTasks) {
            // Called when the operation starts
        }

        @Override
        public void beginTask(String title, int totalWork) {
            this.totalWork = totalWork;
            this.completedWork = 0;
            this.taskTitle = title;
            sendProgress(0);
        }

        @Override
        public void update(int completed) {
            this.completedWork += completed;
            int percentage = (int) ((completedWork * 100.0) / totalWork);
            sendProgress(percentage);
        }

        @Override
        public void endTask() {
            sendProgress(100);
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void showDuration(boolean enabled) {
            // This method is called to enable/disable duration display
            // We don't need to implement anything here as we handle progress differently
        }

        private void sendProgress(int percentage) {
            try {
                Map<String, Object> progressData = new HashMap<>();
                progressData.put("type", "progress");
                progressData.put("task", taskTitle);
                progressData.put("percentage", percentage);
                emitter.send(SseEmitter.event()
                    .data(progressData)
                    .name("progress"));
            } catch (IOException e) {
                logger.error("Error sending progress update: {}", e.getMessage());
            }
        }
    }

    private String formatDiffOutput(List<DiffEntry> diffs, Repository repository) throws IOException {
        // Group diffs by change type
        Map<DiffEntry.ChangeType, List<DiffEntry>> groupedDiffs = diffs.stream()
                .collect(Collectors.groupingBy(DiffEntry::getChangeType));

        StringBuilder formattedOutput = new StringBuilder();
        
        // Process each change type in order: ADD, MODIFY, DELETE
        processChangeTypeGroup(groupedDiffs, DiffEntry.ChangeType.ADD, "New Files", formattedOutput);
        processChangeTypeGroup(groupedDiffs, DiffEntry.ChangeType.MODIFY, "Modified Files", formattedOutput);
        processChangeTypeGroup(groupedDiffs, DiffEntry.ChangeType.DELETE, "Deleted Files", formattedOutput);
        
        String result = formattedOutput.toString().trim();
        // Ensure consistent line endings and escape for JSON
        return result.replace("\r\n", "\n").replace("\n", "\\n");
    }

    private void processChangeTypeGroup(Map<DiffEntry.ChangeType, List<DiffEntry>> groupedDiffs, 
                                      DiffEntry.ChangeType changeType, 
                                      String header,
                                      StringBuilder output) {
        List<DiffEntry> diffs = groupedDiffs.getOrDefault(changeType, Collections.emptyList());
        if (!diffs.isEmpty()) {
            output.append(header).append(" (").append(diffs.size()).append("):\n");
            output.append("----------------------------------------\n\n");
            
            // Sort files alphabetically
            List<String> sortedFiles = diffs.stream()
                .map(diff -> changeType == DiffEntry.ChangeType.DELETE ? diff.getOldPath() : diff.getNewPath())
                .sorted()
                .collect(Collectors.toList());
            
            for (String file : sortedFiles) {
                output.append("  • ").append(file).append("\n");
            }
            output.append("\n"); // Add extra newline between sections
        }
    }

    private void safeCheckoutBranch(Git git, String branchName) throws GitAPIException {
        try {
            // First try to delete the branch if it exists
            try {
                git.branchDelete()
                    .setBranchNames(branchName)
                    .setForce(true)
                    .call();
            } catch (Exception e) {
                // Ignore errors during branch deletion
                logger.debug("Branch {} doesn't exist or couldn't be deleted: {}", branchName, e.getMessage());
            }
            
            // Now checkout the branch fresh from origin
            git.checkout()
                .setName(branchName)
                .setCreateBranch(true)
                .setStartPoint("origin/" + branchName)
                .call();
        } catch (Exception e) {
            logger.error("Error checking out branch {}: {}", branchName, e.getMessage());
            throw e;
        }
    }
}
