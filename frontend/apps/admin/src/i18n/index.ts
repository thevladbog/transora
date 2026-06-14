import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import ruCommon from './locales/ru/common.json';
import ruNav from './locales/ru/nav.json';
import ruAuth from './locales/ru/auth.json';
import ruDashboard from './locales/ru/dashboard.json';
import ruUsers from './locales/ru/users.json';
import ruRefundPolicies from './locales/ru/refundPolicies.json';
import ruPoints from './locales/ru/points.json';
import ruNomenclature from './locales/ru/nomenclature.json';
import ruTariffProfiles from './locales/ru/tariffProfiles.json';
import ruStations from './locales/ru/stations.json';
import ruAgents from './locales/ru/agents.json';
import ruTrips from './locales/ru/trips.json';
import ruAnnouncements from './locales/ru/announcements.json';
import ruDispatcher from './locales/ru/dispatcher.json';
import ruSettings from './locales/ru/settings.json';

import enCommon from './locales/en/common.json';
import enNav from './locales/en/nav.json';
import enAuth from './locales/en/auth.json';
import enDashboard from './locales/en/dashboard.json';
import enUsers from './locales/en/users.json';
import enRefundPolicies from './locales/en/refundPolicies.json';
import enPoints from './locales/en/points.json';
import enNomenclature from './locales/en/nomenclature.json';
import enTariffProfiles from './locales/en/tariffProfiles.json';
import enStations from './locales/en/stations.json';
import enAgents from './locales/en/agents.json';
import enTrips from './locales/en/trips.json';
import enAnnouncements from './locales/en/announcements.json';
import enDispatcher from './locales/en/dispatcher.json';
import enSettings from './locales/en/settings.json';

export const LOCALE_STORAGE_KEY = 'transora.locale';
export const SUPPORTED_LOCALES = ['ru', 'en'] as const;
export type AppLocale = (typeof SUPPORTED_LOCALES)[number];

function readStoredLocale(): AppLocale | null {
  const stored = localStorage.getItem(LOCALE_STORAGE_KEY);
  if (stored === 'ru' || stored === 'en') {
    return stored;
  }
  return null;
}

export function getInitialLocale(): AppLocale {
  return readStoredLocale() ?? 'ru';
}

void i18n.use(initReactI18next).init({
  resources: {
    ru: {
      common: ruCommon,
      nav: ruNav,
      auth: ruAuth,
      dashboard: ruDashboard,
      users: ruUsers,
      refundPolicies: ruRefundPolicies,
      points: ruPoints,
      nomenclature: ruNomenclature,
      tariffProfiles: ruTariffProfiles,
      stations: ruStations,
      agents: ruAgents,
      trips: ruTrips,
      announcements: ruAnnouncements,
      dispatcher: ruDispatcher,
      settings: ruSettings,
    },
    en: {
      common: enCommon,
      nav: enNav,
      auth: enAuth,
      dashboard: enDashboard,
      users: enUsers,
      refundPolicies: enRefundPolicies,
      points: enPoints,
      nomenclature: enNomenclature,
      tariffProfiles: enTariffProfiles,
      stations: enStations,
      agents: enAgents,
      trips: enTrips,
      announcements: enAnnouncements,
      dispatcher: enDispatcher,
      settings: enSettings,
    },
  },
  lng: getInitialLocale(),
  fallbackLng: 'ru',
  defaultNS: 'common',
  interpolation: { escapeValue: false },
});

export function setAppLocale(locale: AppLocale) {
  localStorage.setItem(LOCALE_STORAGE_KEY, locale);
  void i18n.changeLanguage(locale);
}

export default i18n;
