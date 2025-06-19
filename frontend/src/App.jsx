import React, { useState, useEffect } from 'react';
import {
  Container,
  TextField,
  Button,
  Box,
  Typography,
  CircularProgress,
  Paper,
  Alert,
  LinearProgress
} from '@mui/material';
import axios from 'axios';

// Custom styles for components
const styles = {
  container: {
    minHeight: '100vh',
    background: 'linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%)',
    padding: '2rem',
  },
  card: {
    maxWidth: '800px',
    margin: '0 auto',
    backdropFilter: 'blur(10px)',
    backgroundColor: 'rgba(255, 255, 255, 0.9)',
    borderRadius: '16px',
    padding: '2rem',
    boxShadow: '0 8px 32px 0 rgba(31, 38, 135, 0.15)',
  },
  title: {
    color: '#1a237e',
    marginBottom: '2rem',
    textAlign: 'center',
    fontWeight: 700,
  },
  button: {
    textTransform: 'none',
    borderRadius: '8px',
    padding: '10px 24px',
    transition: 'all 0.2s ease-in-out',
    fontWeight: 600,
    '&:hover': {
      transform: 'translateY(-2px)',
      boxShadow: '0 5px 15px rgba(0,0,0,0.1)',
    },
  },
  paper: {
    transition: 'transform 0.2s ease-in-out',
    '&:hover': {
      transform: 'translateY(-4px)',
    },
  },
  resultBox: {
    backgroundColor: '#f5f5f5',
    borderRadius: '8px',
    padding: '1rem',
    maxHeight: '600px',
    overflowY: 'auto',
    overflowX: 'auto',
  },
};

function App() {
  const [sourceBranch, setSourceBranch] = useState('');
  const [targetBranch, setTargetBranch] = useState('');
  const [repoUrl, setRepoUrl] = useState('');
  const [gitToken, setGitToken] = useState('');
  const [loading, setLoading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [currentTask, setCurrentTask] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const API_BASE_URL = 'http://localhost:8080/api/git';
  
  // Configure axios defaults
  axios.defaults.baseURL = API_BASE_URL;
  axios.defaults.headers.common['Content-Type'] = 'application/json';

  // Set up SSE connection for progress updates
  useEffect(() => {
    const eventSource = new EventSource('http://localhost:8080/api/git/progress');
    
    eventSource.onmessage = (event) => {
      const data = JSON.parse(event.data);
      if (data.type === 'progress') {
        setProgress(data.percentage);
        setCurrentTask(data.task);
      }
    };

    eventSource.onerror = (error) => {
      console.error('Error with SSE connection:', error);
      eventSource.close();
    };

    return () => {
      eventSource.close();
    };
  }, []);

  const handleMerge = async () => {
    if (!repoUrl || !gitToken) {
      setError('Please provide repository URL and Git token');
      return;
    }
    try {
      setLoading(true);
      setProgress(30);
      setError(null);
      const response = await axios.post(`${API_BASE_URL}/merge`, {
        sourceBranch,
        targetBranch,
        repositoryUrl: repoUrl,
        token: gitToken,
      });
      setProgress(100);
      setResult({
        type: 'merge',
        data: response.data,
        message: `Merge ${response.data.success ? 'successful' : 'failed'}: ${response.data.status}`
      });
    } catch (err) {
      setError(err.response?.data?.error || 'Failed to merge branches');
    } finally {
      setLoading(false);
    }
  };

  const handleDiff = async () => {
    if (!repoUrl || !gitToken) {
      setError('Please provide repository URL and Git token');
      return;
    }
    try {
      setLoading(true);
      setProgress(30);
      setError(null);
      console.log('Requesting diff for branches:', { sourceBranch, targetBranch, repoUrl });
      const response = await axios.get(`${API_BASE_URL}/diff`, {
        params: {
          sourceBranch,
          targetBranch,
          repositoryUrl: repoUrl,
          token: gitToken,
        }
      });
      console.log('Received response:', response.data);
      setProgress(100);
      
      // Ensure we have the correct data structure
      const diffData = response.data;
      if (!diffData || typeof diffData.diff !== 'string') {
        throw new Error('Invalid response format from server');
      }

      setResult({
        type: 'diff',
        data: diffData,
        message: 'Diff retrieved successfully'
      });
    } catch (err) {
      console.error('Diff error:', err);
      const errorMessage = err.response?.data?.error || err.message || 'Failed to get diff';
      setError(`Error: ${errorMessage} (Status: ${err.response?.status || 'unknown'})`);
    } finally {
      setLoading(false);
    }
  };

  const handleConflicts = async () => {
    if (!repoUrl || !gitToken) {
      setError('Please provide repository URL and Git token');
      return;
    }
    try {
      setLoading(true);
      setProgress(30);
      setError(null);
      const response = await axios.get(`${API_BASE_URL}/conflicts`, {
        params: {
          repositoryUrl: repoUrl,
          token: gitToken,
        }
      });
      setProgress(100);
      setResult({
        type: 'conflicts',
        data: response.data,
        message: response.data.conflicts.length 
          ? `Found ${response.data.conflicts.length} conflicts` 
          : 'No conflicts found'
      });
    } catch (err) {
      setError(err.response?.data?.error || 'Failed to get conflicts');
    } finally {
      setLoading(false);
    }
  };

  const parseDiffOutput = (diffData) => {
  if (!diffData || typeof diffData !== 'object') {
    console.error('Invalid diff data:', diffData);
    return {
      newFiles: [],
      modifiedFiles: [],
      deletedFiles: []
    };
  }

  const diffString = diffData.diff || '';
  if (typeof diffString !== 'string') {
    console.error('Invalid diff string:', diffString);
    return {
      newFiles: [],
      modifiedFiles: [],
      deletedFiles: []
    };
  }

  // Skip the header and split the rest into sections
  const sections = diffString.split('\\n\\n').filter(section => 
    !section.includes('Changes Summary') && 
    !section.includes('==============')
  );
  
  const result = {
    newFiles: [],
    modifiedFiles: [],
    deletedFiles: []
  };

  let currentSection = null;
  for (const section of sections) {
    if (section.includes('New Files')) {
      currentSection = 'new';
    } else if (section.includes('Modified Files')) {
      currentSection = 'modified';
    } else if (section.includes('Deleted Files')) {
      currentSection = 'deleted';
    } else if (currentSection && section.trim()) {
      const files = section
        .split('\\n')
        .filter(line => line.trim().startsWith('•'))
        .map(line => line.trim().substring(2).trim());
      
      if (currentSection === 'new') {
        result.newFiles.push(...files);
      } else if (currentSection === 'modified') {
        result.modifiedFiles.push(...files);
      } else if (currentSection === 'deleted') {
        result.deletedFiles.push(...files);
      }
    }
  }
    return result;
  };

  const DiffResultCard = ({ title, files, color }) => (
    <Paper elevation={3} sx={{ p: 3, mb: 2, borderTop: `4px solid ${color}` }}>
      <Typography variant="h6" gutterBottom sx={{ color: color, display: 'flex', alignItems: 'center' }}>
        {title} ({files.length})
      </Typography>
      <Box sx={{ maxHeight: '200px', overflowY: 'auto' }}>
        {files.map((file, index) => (
          <Typography 
            key={index} 
            component="div" 
            sx={{ 
              fontSize: '0.9rem',
              fontFamily: 'monospace',
              padding: '4px 8px',
              borderRadius: '4px',
              '&:hover': { backgroundColor: '#f5f5f5' },
              marginBottom: '4px',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word'
            }}
          >
            • {file}
          </Typography>
        ))}
      </Box>
    </Paper>
  );

  const renderDiffResult = (diffString) => {
    try {
      const parsedDiff = parseDiffOutput(diffString);              return (
                <Box>
                  <DiffResultCard 
                    title="New Files" 
                    files={parsedDiff.newFiles} 
                    color="#2e7d32" // Green
                  />
                  <DiffResultCard 
                    title="Modified Files" 
                    files={parsedDiff.modifiedFiles} 
                    color="#1976d2" // Blue
                  />
                  <DiffResultCard 
                    title="Deleted Files" 
                    files={parsedDiff.deletedFiles} 
                    color="#d32f2f" // Red
                  />
        </Box>
      );
    } catch (error) {
      console.error('Error parsing diff:', error);
      return (
        <pre style={{
          margin: 0,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          fontFamily: 'monospace',
          fontSize: '0.9rem',
          lineHeight: '1.5'
        }}>
          {diffString}
        </pre>
      );
    }
  };

  return (
    <Box sx={styles.container}>
      <Container>
        <Box sx={styles.card}>
          <Typography variant="h4" sx={styles.title}>
            Git Branch Manager
          </Typography>

          <Paper elevation={3} sx={{ ...styles.paper, p: 3, mb: 3 }}>
            <Typography variant="h6" gutterBottom sx={{ color: '#1a237e' }}>
              Git Configuration
            </Typography>
            <TextField
              fullWidth
              label="Repository URL"
              value={repoUrl}
              onChange={(e) => setRepoUrl(e.target.value)}
              margin="normal"
              variant="outlined"
              helperText="e.g., https://github.com/username/repo.git"
              sx={{
                '& .MuiOutlinedInput-root': {
                  '&:hover fieldset': {
                    borderColor: '#1a237e',
                  },
                },
              }}
            />
            <TextField
              fullWidth
              label="Git Token"
              value={gitToken}
              onChange={(e) => setGitToken(e.target.value)}
              margin="normal"
              type="password"
              variant="outlined"
              helperText="Your GitHub Personal Access Token"
              sx={{
                '& .MuiOutlinedInput-root': {
                  '&:hover fieldset': {
                    borderColor: '#1a237e',
                  },
                },
              }}
            />
          </Paper>

          <Paper elevation={3} sx={{ ...styles.paper, p: 3, mb: 3 }}>
            <Typography variant="h6" gutterBottom sx={{ color: '#1a237e' }}>
              Branch Configuration
            </Typography>
            <TextField
              fullWidth
              label="Source Branch"
              value={sourceBranch}
              onChange={(e) => setSourceBranch(e.target.value)}
              margin="normal"
              variant="outlined"
              sx={{
                '& .MuiOutlinedInput-root': {
                  '&:hover fieldset': {
                    borderColor: '#1a237e',
                  },
                },
              }}
            />
            <TextField
              fullWidth
              label="Target Branch"
              value={targetBranch}
              onChange={(e) => setTargetBranch(e.target.value)}
              margin="normal"
              variant="outlined"
              sx={{
                '& .MuiOutlinedInput-root': {
                  '&:hover fieldset': {
                    borderColor: '#1a237e',
                  },
                },
              }}
            />
          </Paper>

          <Paper elevation={3} sx={{ ...styles.paper, p: 3, mb: 3 }}>
            <Typography variant="h6" gutterBottom sx={{ color: '#1a237e' }}>
              Actions
            </Typography>
            <Box sx={{ display: 'flex', gap: 2, mb: 2 }}>
              <Button
                variant="contained"
                onClick={handleMerge}
                disabled={loading}
                sx={{
                  ...styles.button,
                  backgroundColor: '#1a237e',
                  flex: 1,
                  '&:hover': {
                    backgroundColor: '#0d47a1',
                  },
                }}
              >
                Merge Branches
              </Button>
              <Button
                variant="contained"
                onClick={handleDiff}
                disabled={loading}
                sx={{
                  ...styles.button,
                  backgroundColor: '#0d47a1',
                  flex: 1,
                  '&:hover': {
                    backgroundColor: '#1565c0',
                  },
                }}
              >
                Get Diff
              </Button>
              <Button
                variant="contained"
                onClick={handleConflicts}
                disabled={loading}
                sx={{
                  ...styles.button,
                  backgroundColor: '#283593',
                  flex: 1,
                  '&:hover': {
                    backgroundColor: '#303f9f',
                  },
                }}
              >
                Check Conflicts
              </Button>
            </Box>
          </Paper>

          {/* Display progress bar */}
          {progress > 0 && progress < 100 && (
            <Box sx={{ width: '100%', mt: 2, mb: 2 }}>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                {currentTask || 'Processing'} - {progress}%
              </Typography>
              <LinearProgress variant="determinate" value={progress} />
            </Box>
          )}

          {loading && (
            <Box sx={{ width: '100%', mb: 3 }}>
              <LinearProgress 
                variant="determinate" 
                value={progress} 
                sx={{
                  height: 8,
                  borderRadius: 4,
                  backgroundColor: 'rgba(26, 35, 126, 0.2)',
                  '& .MuiLinearProgress-bar': {
                    backgroundColor: '#1a237e',
                  },
                }}
              />
              <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', mt: 2 }}>
                <CircularProgress size={24} sx={{ mr: 1, color: '#1a237e' }} />
                <Typography variant="body2" color="textSecondary">
                  Processing...
                </Typography>
              </Box>
            </Box>
          )}

          {error && (
            <Alert 
              severity="error" 
              sx={{ 
                mb: 3,
                borderRadius: 2,
                '& .MuiAlert-icon': {
                  color: '#d32f2f',
                },
              }}
            >
              {error}
            </Alert>
          )}            {result && (
            <Paper elevation={3} sx={{ ...styles.paper, p: 3 }}>
              {result.type === 'diff' ? (
                renderDiffResult(result.data)
              ) : (
                <>
                  <Typography variant="h6" gutterBottom sx={{ color: '#1a237e' }}>
                    Result
                  </Typography>
                  <Box sx={styles.resultBox}>
                    <pre style={{
                      margin: 0,
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                      fontFamily: 'monospace',
                      fontSize: '0.9rem',
                      lineHeight: '1.5'
                    }}>
                      {JSON.stringify(result.data, null, 2)}
                    </pre>
                  </Box>
                </>
              )}
              {/* Add debug info during development */}
              {process.env.NODE_ENV === 'development' && (
                <Box sx={{ mt: 2, p: 2, backgroundColor: '#f5f5f5', borderRadius: 1 }}>
                  <Typography variant="caption" component="div" sx={{ fontFamily: 'monospace' }}>
                    Debug: {JSON.stringify({ resultType: result.type, dataType: typeof result.data })}
                  </Typography>
                </Box>
              )}
            </Paper>
          )}

        </Box>
      </Container>
    </Box>
  );
}

export default App
