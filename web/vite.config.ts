import { defineConfig } from 'vite';

// The game serves this folder from the mod jar at http://127.0.0.1:<port>/<token>/, so every URL in the output
// must be relative (base './') to keep the token in it, and the file names must be fixed: the server only serves
// plain names and the dev check asks for assets/app.js by name.
export default defineConfig({
  base: './',
  esbuild: { jsx: 'automatic', jsxImportSource: 'preact' },
  build: {
    outDir: '../src/main/resources/assets/foxstweaks/web',
    emptyOutDir: true,
    modulePreload: false,
    sourcemap: false,
    target: 'es2020',
    cssCodeSplit: false,
    rollupOptions: {
      output: {
        entryFileNames: 'assets/app.js',
        chunkFileNames: 'assets/[name].js',
        assetFileNames: (info) => (info.name?.endsWith('.css') ? 'assets/app.css' : 'assets/[name][extname]'),
      },
    },
  },
});
