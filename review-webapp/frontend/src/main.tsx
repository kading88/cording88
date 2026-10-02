import React from 'react';
import ReactDOM from 'react-dom/client';
import { App as AntApp } from 'antd';
import 'antd/dist/reset.css';
import './styles.css';
import App from './App';
import { I18nProvider } from './i18n';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><I18nProvider><AntApp><App/></AntApp></I18nProvider></React.StrictMode>,
);
