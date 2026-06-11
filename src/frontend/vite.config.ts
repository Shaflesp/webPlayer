import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Tomcat dev server port — change if yours differs
const TOMCAT = 'http://localhost:8080/webPlayer';

export default defineConfig({
  plugins: [react()],

  build: {
    // Output goes straight into the WAR's webapp directory
    outDir:     '../main/webapp',
    emptyOutDir: false,   // never wipe WEB-INF / META-INF
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
