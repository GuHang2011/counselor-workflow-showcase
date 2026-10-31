import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { icons } from './icons';
import 'element-plus/dist/index.css';
import './style.css';
import './prelaunch.css';
import './workspace.css';
import App from './App.vue';
const app = createApp(App);
for (const [name, component] of Object.entries(icons)) app.component(name, component);
// The pinned Element Plus locale lacks the dialog close label used by its component.
const locale = { ...zhCn, el: { ...zhCn.el, dialog: { ...(zhCn.el as Record<string, any>).dialog, close: '关闭对话框' } } };
app.use(ElementPlus, { locale }).mount('#app');
