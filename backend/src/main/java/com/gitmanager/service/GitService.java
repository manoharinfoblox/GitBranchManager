package com.gitmanager.service;

import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GitService {

    private SseEmitter progressEmitter;
    private static final Logger logger = LoggerFactory.getLogger(GitService.class);

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

    public String getDiff(String sourceBranch, String targetBranch, String repositoryUrl, String token) throws GitAPIException, IOException {
        logger.info("Getting diff between {} and {} for repo {}", sourceBranch, targetBranch, repositoryUrl);
        try (Repository repository = openRepository(repositoryUrl, token); Git git = new Git(repository)) {
            logger.debug("Repository opened at: {}", repository.getDirectory());
            
            // Fetch latest changes
            git.fetch()
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token))
                .call();
            
            // Helper method to safely checkout branch
            checkoutBranch(git, sourceBranch, token);
            checkoutBranch(git, targetBranch, token);

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
            
            StringBuilder diffOutput = new StringBuilder();
            diffOutput.append(String.format("Diff between %s and %s\n", sourceBranch, targetBranch));
            diffOutput.append("----------------------------------------\n\n");

            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repository);
                formatter.setContext(3); // Show 3 lines of context
                
                for (DiffEntry diff : diffs) {
                    out.reset();
                    formatter.format(diff);
                    
                    diffOutput.append(String.format("File: %s\n", diff.getNewPath()));
                    diffOutput.append(String.format("Change Type: %s\n", diff.getChangeType()));
                    diffOutput.append("----------------------------------------\n");
                    diffOutput.append(out.toString("UTF-8"));
                    diffOutput.append("\n\n");
                }
            }
            
            String result = diffOutput.toString();
            logger.debug("Diff completed with {} files changed", diffs.size());
            return result;
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

    private void checkoutBranch(Git git, String branchName, String token) throws GitAPIException {
        try {
            // First try to checkout the existing branch
            git.checkout()
                .setName(branchName)
                .call();
            
            // Update it to latest remote version
            git.pull()
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token))
                .call();
        } catch (GitAPIException e) {
            // If the branch doesn't exist locally, create it
            git.checkout()
                .setCreateBranch(true)
                .setName(branchName)
                .setStartPoint("origin/" + branchName)
                .call();
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
            // Implementation not required for our use case
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
}
