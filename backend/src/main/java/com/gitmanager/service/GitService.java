package com.gitmanager.service;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
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
    private static final Logger logger = LoggerFactory.getLogger(GitService.class);
    
    private SseEmitter progressEmitter;

    public void setProgressEmitter(SseEmitter emitter) {
        this.progressEmitter = emitter;
    }

    public MergeResult mergeBranches(String sourceBranch, String targetBranch, String repositoryUrl, String token) throws GitAPIException, IOException {
        try (Repository repository = openRepository(repositoryUrl, token); Git git = new Git(repository)) {
            logger.info("Starting merge process: {} into {}", sourceBranch, targetBranch);
            UsernamePasswordCredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider("token", token);
            
            // First, fetch all changes
            logger.debug("Fetching latest changes...");
            git.fetch()
                .setCredentialsProvider(credentialsProvider)
                .setRemoveDeletedRefs(true)
                .call();
            
            try {
                logger.debug("Attempting to checkout target branch: {}", targetBranch);
                
                try {
                    // First try to checkout the existing branch
                    git.checkout()
                        .setName(targetBranch)
                        .setCreateBranch(false)
                        .call();
                    logger.debug("Successfully checked out existing branch: {}", targetBranch);
                } catch (Exception checkoutError) {
                    logger.debug("Could not checkout existing branch, trying to create new one: {}", checkoutError.getMessage());
                    
                    // If checkout fails, try to delete and recreate
                    try {
                        git.branchDelete()
                            .setBranchNames(targetBranch)
                            .setForce(true)
                            .call();
                    } catch (Exception deleteError) {
                        logger.debug("Branch deletion failed (might not exist): {}", deleteError.getMessage());
                    }
                    
                    // Create new branch tracking remote
                    git.checkout()
                        .setName(targetBranch)
                        .setCreateBranch(true)
                        .setStartPoint("origin/" + targetBranch)
                        .setForce(true)
                        .call();
                    
                    logger.debug("Created and checked out new branch: {}", targetBranch);
                }
                
                // Pull latest changes
                logger.debug("Pulling latest changes for target branch");
                git.pull()
                    .setCredentialsProvider(credentialsProvider)
                    .call();
                
                logger.info("Target branch {} prepared for merge", targetBranch);
                
                // Merge source branch into target branch
                logger.debug("Starting merge from {} into {}", sourceBranch, targetBranch);
                MergeCommand mergeCommand = git.merge();
                mergeCommand.include(repository.resolve("origin/" + sourceBranch));
                mergeCommand.setCommit(true);
                mergeCommand.setMessage("Merge " + sourceBranch + " into " + targetBranch);
                mergeCommand.setFastForward(MergeCommand.FastForwardMode.NO_FF);  // Force a merge commit
                
                MergeResult result = mergeCommand.call();
                
                if (result.getMergeStatus().isSuccessful()) {
                    // Push the merge result
                    logger.debug("Pushing merge result...");
                    git.push()
                        .setCredentialsProvider(credentialsProvider)
                        .call();
                    logger.info("Successfully pushed merge result");
                }
                
                logger.info("Merge completed with status: {}", result.getMergeStatus());
                return result;
            } catch (Exception e) {
                logger.error("Error during merge operation: {}", e.getMessage());
                e.printStackTrace();
                throw new GitAPIException("Merge operation failed: " + e.getMessage(), e) {
                    private static final long serialVersionUID = 1L;
                };
            }
        } catch (Exception e) {
            logger.error("Critical error during merge process: {}", e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    public String getDiff(String sourceBranch, String targetBranch, String repositoryUrl, String token) throws GitAPIException, IOException {
        logger.info("Getting diff between {} and {} for repo {}", sourceBranch, targetBranch, repositoryUrl);
        try (Repository repository = openRepository(repositoryUrl, token); Git git = new Git(repository)) {
            logger.debug("Repository opened at: {}", repository.getDirectory());
            
            // Fetch latest changes
            git.fetch()
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token))
                .call();
            
            // Safely check out branches by first deleting them if they exist
            safeCheckoutBranch(git, sourceBranch, token);
            safeCheckoutBranch(git, targetBranch, token);

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

    private void safeCheckoutBranch(Git git, String branchName, String token) throws GitAPIException {
        try {
            logger.info("Starting safe checkout of branch: {}", branchName);
            
            UsernamePasswordCredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider("token", token);
            
            // First, fetch the latest changes
            logger.debug("Fetching latest changes from remote...");
            try {
                git.fetch()
                    .setRemoveDeletedRefs(true)
                    .setCredentialsProvider(credentialsProvider)
                    .call();
                logger.debug("Fetch completed successfully");
            } catch (Exception e) {
                logger.error("Failed to fetch: {}", e.getMessage());
                e.printStackTrace();
                throw e;
            }

            try {
                // First attempt: try to checkout the existing branch
                logger.debug("Attempting to checkout branch without creation: {}", branchName);
                git.checkout()
                    .setName(branchName)
                    .setCreateBranch(false)
                    .call();
                
                // If successful, just pull the latest changes
                git.pull()
                    .setCredentialsProvider(credentialsProvider)
                    .call();
                
                logger.info("Successfully checked out existing branch and pulled changes: {}", branchName);
            } catch (Exception firstAttempt) {
                logger.debug("Could not checkout existing branch, attempting to recreate: {}", firstAttempt.getMessage());
                
                try {
                    // Clean up any existing refs
                    git.branchDelete()
                        .setBranchNames(branchName)
                        .setForce(true)
                        .call();
                } catch (Exception deleteError) {
                    logger.debug("Could not delete branch, might not exist: {}", deleteError.getMessage());
                }
                
                try {
                    // Reset any pending changes
                    git.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .call();
                } catch (Exception resetError) {
                    logger.debug("Reset failed, continuing anyway: {}", resetError.getMessage());
                }
                
                // Create and checkout fresh branch
                git.checkout()
                    .setName(branchName)
                    .setCreateBranch(true)
                    .setStartPoint("origin/" + branchName)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
                
                // Pull to ensure we're up to date
                git.pull()
                    .setCredentialsProvider(credentialsProvider)
                    .call();
                
                logger.info("Successfully recreated and checked out branch: {}", branchName);
            }
        } catch (Exception e) {
            String errorMsg = String.format("Failed to checkout branch %s: %s", branchName, e.getMessage());
            logger.error(errorMsg, e);
            throw new GitAPIException(errorMsg, e) {
                private static final long serialVersionUID = 1L;
            };
        }
    }
}
