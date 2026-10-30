import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET?.trim() || 'http://127.0.0.1:9999';

export default defineConfig({
	plugins: [vue()],
	resolve: { alias: { '@': resolve(__dirname, 'src'), '/@': resolve(__dirname, 'src') } },
	base: './',
	// Exercise the production CSP during acceptance; development HMR keeps its own policy.
	preview: { headers: {
		'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
		'X-Content-Type-Options': 'nosniff',
		'X-Frame-Options': 'DENY',
		'Referrer-Policy': 'same-origin',
	} },
	server: {
		host: '127.0.0.1',
		port: 8888,
		proxy: { '/api': { target: apiProxyTarget, changeOrigin: false, rewrite: (path) => path.replace(/^\/api/, '') } },
	},
	build: { chunkSizeWarningLimit: 1500 },
});
