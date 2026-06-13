import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const TOMCAT = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],

  build: {
    outDir:     'dist',
    emptyOutDir: true,
    assetsDir:  'assets',
    sourcemap:  false,
  },

  server: {
    port: 5173,
    proxy: {
      '/MPDServlet':    { target: TOMCAT, changeOrigin: true },
      '/ArtServlet':    { target: TOMCAT, changeOrigin: true },
      '/AudioServlet':  { target: TOMCAT, changeOrigin: true },
      '/ConfigServlet': { target: TOMCAT, changeOrigin: true },
      // SSE needs the connection to stay open
      '/FifoServlet':   { target: TOMCAT, changeOrigin: true, ws: false },
    },
  },
});