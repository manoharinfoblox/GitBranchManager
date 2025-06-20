package com.gitmanager.controller;

import com.gitmanager.service.GitService;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.MergeResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/git")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173")
public class GitController {

    private final GitService gitService;

    @PostMapping("/merge")
    public ResponseEntity<?> mergeBranches(@RequestBody Map<String, String> request) {
        try {
            String sourceBranch = request.get("sourceBranch");
            String targetBranch = request.get("targetBranch");
            String repositoryUrl = request.get("repositoryUrl");
            String token = request.get("token");
            
            if (sourceBranch == null || targetBranch == null || repositoryUrl == null || token == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing required parameters"));
            }
            
            MergeResult result = gitService.mergeBranches(sourceBranch, targetBranch, repositoryUrl, token);
            
            if (result.getMergeStatus().isSuccessful()) {
                return ResponseEntity.ok(Map.of(
                    "status", "Success",
                    "success", true,
                    "message", "Successfully merged " + sourceBranch + " into " + targetBranch
                ));
            } else if (result.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                return ResponseEntity.ok(Map.of(
                    "status", "Conflict",
                    "success", false,
                    "message", "Merge conflicts detected",
                    "conflicts", result.getConflicts().keySet()
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                    "status", result.getMergeStatus().toString(),
                    "success", false,
                    "message", "Merge failed: " + result.getMergeStatus().toString()
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "status", "Failed",
                "success", false
            ));
        }
    }

    @GetMapping("/diff")
    public ResponseEntity<?> getDiff(
            @RequestParam String sourceBranch,
            @RequestParam String targetBranch,
            @RequestParam String repositoryUrl,
            @RequestParam String token) {
        try {
            if (sourceBranch == null || sourceBranch.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Source branch cannot be empty"));
            }
            if (targetBranch == null || targetBranch.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Target branch cannot be empty"));
            }
            if (repositoryUrl == null || repositoryUrl.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Repository URL cannot be empty"));
            }
            if (token == null || token.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Git token cannot be empty"));
            }
            
            String diff = gitService.getDiff(sourceBranch, targetBranch, repositoryUrl, token);
            return ResponseEntity.ok(Map.of("diff", diff));
        } catch (Exception e) {
            e.printStackTrace(); // This will log the full stack trace
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Failed to get diff: " + e.getMessage(),
                "details", e.getClass().getName()
            ));
        }
    }

    @GetMapping("/conflicts")
    public ResponseEntity<?> getConflicts(
            @RequestParam String repositoryUrl,
            @RequestParam String token) {
        try {
            if (repositoryUrl == null || repositoryUrl.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Repository URL cannot be empty"));
            }
            if (token == null || token.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Git token cannot be empty"));
            }
            
            Set<String> conflicts = gitService.getConflicts(repositoryUrl, token);
            return ResponseEntity.ok(Map.of("conflicts", conflicts));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress() {
        SseEmitter emitter = new SseEmitter();
        gitService.setProgressEmitter(emitter);
        return emitter;
    }
}
