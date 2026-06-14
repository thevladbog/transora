import { useState } from 'react';
import { Navigate, useLocation } from 'react-router';
import { Alert, Button, Card, Spinner } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import { FormTextField } from '@/components/ui/FormFields';
import { LocaleThemeToolbar } from '@/components/layout/LocaleThemeToolbar';
import { useAuth } from './auth-context';

export function LoginPage() {
  const { t } = useTranslation('auth');
  const { isAuthenticated, isLoading, loginWithCredentials } = useAuth();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const loginSchema = z.object({
    login: z.string().min(1, t('loginRequired')),
    password: z.string().min(1, t('passwordRequired')),
  });

  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (isLoading) {
    return (
      <div className="login-shell flex min-h-screen items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  if (isAuthenticated) {
    return <Navigate to={from} replace />;
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    const parsed = loginSchema.safeParse({ login, password });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? t('invalidData'));
      return;
    }

    setSubmitting(true);
    try {
      await loginWithCredentials(parsed.data.login, parsed.data.password);
    } catch {
      setError(t('invalidCredentials'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="login-shell relative flex min-h-screen flex-col items-center justify-center p-4">
      <div className="absolute right-4 top-4">
        <LocaleThemeToolbar />
      </div>
      <Card className="w-full max-w-md border border-border/60 shadow-lg">
        <Card.Header>
          <Card.Title>{t('title')}</Card.Title>
          <Card.Description>{t('description')}</Card.Description>
        </Card.Header>
        <Card.Content>
          <form className="transora-form-stack" onSubmit={handleSubmit}>
            <FormTextField
              isRequired
              label={t('login')}
              name="login"
              value={login}
              onChange={setLogin}
              inputProps={{ autoComplete: 'username', className: 'font-mono' }}
            />
            <FormTextField
              isRequired
              label={t('password')}
              name="password"
              type="password"
              value={password}
              onChange={setPassword}
              inputProps={{ autoComplete: 'current-password', type: 'password' }}
            />

            {error ? (
              <Alert status="danger">
                <Alert.Indicator />
                <Alert.Content>
                  <Alert.Description>{error}</Alert.Description>
                </Alert.Content>
              </Alert>
            ) : null}

            <Button className="w-full" type="submit" variant="primary" isPending={submitting}>
              {t('submit')}
            </Button>
          </form>
        </Card.Content>
      </Card>
    </div>
  );
}
