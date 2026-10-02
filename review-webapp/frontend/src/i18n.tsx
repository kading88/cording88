import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import jaJP from 'antd/locale/ja_JP';
import enUS from 'antd/locale/en_US';
import { ApiError } from './api';
import { translations, type TranslationKey } from './translations';

type Language = 'zh' | 'ja' | 'en';
type Params = Record<string, string | number>;
const locales = { zh: 'zh-CN', ja: 'ja-JP', en: 'en-US' };
const antLocales = { zh: zhCN, ja: jaJP, en: enUS };
const languageKey = 'scx-review-language';
const isLanguage = (value: unknown): value is Language => value === 'zh' || value === 'ja' || value === 'en';
function initialLanguage(): Language {
  try { const saved = localStorage.getItem(languageKey); return isLanguage(saved) ? saved : 'en'; }
  catch { return 'en'; }
}
function translate(language: Language, key: TranslationKey, params: Params = {}) {
  const text = translations[key][language];
  return text.replace(/\{(\w+)\}/g, (match, name: string) => String(params[name] ?? match));
}
const apiErrors: Record<string, TranslationKey> = {
  BAD_CREDENTIALS: "incorrectUsernameOrPassword",
  UNAUTHORIZED: "pleaseSignIn",
  FORBIDDEN: "permissionDeniedOrAuthenticationExpiredRefreshThePage",
  INVALID_INPUT: "checkTheLabelNotesOrSearchFilters",
  LOCKED: "anotherUserIsEditingThisRecordPleaseTryAgainLater",
  LOCK_EXPIRED: "yourEditLockExpiredAcquireItAgainAndCheckTheLatestRecord",
  REASON_REQUIRED: "enterAReasonWhenChangingTheModelLabel",
  NOT_FOUND: "thisRecordDoesNotExistOrHasBeenDeleted",
  NETWORK_ERROR: "cannotConnectToTheServerPleaseTryAgainLater",
};
function errorKey(error: unknown): TranslationKey {
  if (error instanceof ApiError) return apiErrors[error.code] ?? (error.status >= 500 ? "cannotConnectToTheServerPleaseTryAgainLater" : "theOperationFailedPleaseTryAgain");
  if (error instanceof TypeError) return "cannotConnectToTheServerPleaseTryAgainLater";
  return "theOperationFailedPleaseTryAgain";
}
type I18n = {
  language: Language;
  setLanguage: (language: Language) => void;
  t: (key: TranslationKey, params?: Params) => string;
  number: (value: number, options?: Intl.NumberFormatOptions) => string;
  date: (value: string | null) => string;
  errorText: (error: unknown) => string;
};
const I18nContext = createContext<I18n | null>(null);
export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLanguage] = useState<Language>(initialLanguage);
  const t = useCallback((key: TranslationKey, params?: Params) => translate(language, key, params), [language]);
  useEffect(() => {
    document.documentElement.lang = locales[language];
    document.title = 'SCX Review · ' + t("trafficReview");
    try { localStorage.setItem(languageKey, language); } catch { /* Allow switching for this session even when browser storage is disabled. */ }
  }, [language, t]);
  const value = useMemo<I18n>(() => ({
    language, setLanguage, t,
    number: (value, options) => value.toLocaleString(locales[language], options),
    date: value => value ? new Date(value).toLocaleString(locales[language], { hour12: false }) : t("notReviewed"),
    errorText: error => t(errorKey(error)),
  }), [language, t]);
  return <I18nContext.Provider value={value}><ConfigProvider locale={antLocales[language]} theme={{ token: {
    colorPrimary: '#126d5b', colorText: '#233630', colorTextSecondary: '#75837c',
    borderRadius: 8, fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", "Yu Gothic", "Microsoft YaHei", sans-serif',
    controlHeight: 38, colorBorder: '#dfe6e0',
  }, components: { Table: { headerBg: '#f8faf7', headerColor: '#718078' } } }}>{children}</ConfigProvider></I18nContext.Provider>;
}
export function useI18n() {
  const value = useContext(I18nContext);
  if (!value) throw new Error('I18nProvider is required');
  return value;
}
export function LanguageSwitcher() {
  const { language, setLanguage, t } = useI18n();
  return <select className="language-switcher" aria-label={t("language")} value={language} onChange={event => {
    if (isLanguage(event.target.value)) setLanguage(event.target.value);
  }}><option value="zh" lang="zh-CN">{translations.languageNameChinese.zh}</option><option value="ja" lang="ja">{translations.languageNameJapanese.ja}</option><option value="en" lang="en">English</option></select>;
}
