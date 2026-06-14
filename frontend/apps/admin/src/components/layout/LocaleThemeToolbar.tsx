import { LanguageSwitcher } from './LanguageSwitcher';
import { ThemeSwitcher } from './ThemeSwitcher';

export function LocaleThemeToolbar() {
  return (
    <div className="flex items-center gap-2">
      <LanguageSwitcher />
      <ThemeSwitcher />
    </div>
  );
}
