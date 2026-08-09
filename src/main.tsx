import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
import { ToastProvider } from './components/ToastProvider';
import './index.css';

// ─── Root ErrorBoundary — prevents blank white screen on render crash ─────────
class RootErrorBoundary extends React.Component<
  { children: React.ReactNode },
  { hasError: boolean; error: Error | null }
> {
  state = { hasError: false, error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error('[RootErrorBoundary] Render error:', error, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{
          display: 'flex', flexDirection: 'column', alignItems: 'center',
          justifyContent: 'center', height: '100vh', background: '#00000f',
          color: '#fff', gap: '16px', padding: '32px', textAlign: 'center',
        }}>
          <div style={{ fontSize: '40px' }}>⚠️</div>
          <h1 style={{ fontSize: '22px', fontWeight: 700, color: '#00FFFF', margin: 0 }}>
            Something went wrong
          </h1>
          <p style={{ color: 'rgba(255,255,255,0.5)', maxWidth: '440px', fontSize: '13px', lineHeight: 1.6, margin: 0 }}>
            {this.state.error?.message || 'An unexpected rendering error occurred.'}
          </p>
          <button
            onClick={() => this.setState({ hasError: false, error: null })}
            style={{
              marginTop: '8px', padding: '10px 28px', background: 'transparent',
              border: '1px solid #00FFFF', color: '#00FFFF', borderRadius: '10px',
              cursor: 'pointer', fontSize: '14px', fontWeight: 600,
            }}
          >
            Try to Recover
          </button>
          <p style={{ color: 'rgba(255,255,255,0.2)', fontSize: '11px', margin: 0 }}>
            If the error repeats, restart the app.
          </p>
        </div>
      );
    }
    return this.props.children;
  }
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <RootErrorBoundary>
      <ToastProvider position="top-right">
        <App />
      </ToastProvider>
    </RootErrorBoundary>
  </React.StrictMode>
);
