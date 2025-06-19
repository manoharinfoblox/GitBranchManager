import React from 'react';
import { render, screen } from '@testing-library/react';
import App from './App';

test('renders git manager title', () => {
  render(<App />);
  const titleElement = screen.getByText(/Git Branch Manager/i);
  expect(titleElement).toBeInTheDocument();
});
