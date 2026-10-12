import { describe, it, expect } from 'vitest';
import { toUserMessage, ApiError } from './errorMapper';

describe('errorMapper', () => {
  describe('toUserMessage', () => {
    it('returns serverMessage for ApiError', () => {
      const error = new ApiError('Incorrect email or password.', 401);
      expect(toUserMessage(error)).toBe('Incorrect email or password.');
    });

    it('returns timeout message for AbortError', () => {
      const error = new Error('Request aborted');
      error.name = 'AbortError';
      expect(toUserMessage(error)).toBe("VeritasVPN's server didn't respond in time. Check your internet connection and try again.");
    });

    it('returns timeout message for timed out error', () => {
      const error = new Error('Request timed out');
      expect(toUserMessage(error)).toBe("VeritasVPN's server didn't respond in time. Check your internet connection and try again.");
    });

    it('returns cannot reach message for TypeError', () => {
      const error = new TypeError('Failed to fetch');
      expect(toUserMessage(error)).toBe("Can't reach VeritasVPN. Check that you're online and try again.");
    });

    it('returns cannot reach message for network error', () => {
      const error = new Error('Network error');
      expect(toUserMessage(error)).toBe("Can't reach VeritasVPN. Check that you're online and try again.");
    });

    it('returns cannot reach message for error sending request', () => {
      const error = new Error('Error sending request');
      expect(toUserMessage(error)).toBe("Can't reach VeritasVPN. Check that you're online and try again.");
    });

    it('returns secure connection failed for SSL error', () => {
      const error = new Error('SSL certificate error');
      expect(toUserMessage(error)).toBe("Secure connection failed. Check your network (public Wi-Fi may block it) and try again.");
    });

    it('returns generic message for unknown error', () => {
      const error = new Error('Some unknown error');
      expect(toUserMessage(error)).toBe('Something went wrong. Please try again.');
    });

    it('returns generic message for non-Error', () => {
      expect(toUserMessage('string error')).toBe('Something went wrong. Please try again.');
      expect(toUserMessage(null)).toBe('Something went wrong. Please try again.');
      expect(toUserMessage(undefined)).toBe('Something went wrong. Please try again.');
    });

    it('never returns raw error message for unknown errors', () => {
      const error = new Error('Raw error message that should not be shown');
      const message = toUserMessage(error);
      expect(message).not.toBe('Raw error message that should not be shown');
      expect(message).toBe('Something went wrong. Please try again.');
    });
  });
});
