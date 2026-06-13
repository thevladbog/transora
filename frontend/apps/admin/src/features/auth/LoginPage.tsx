import { useState } from 'react';
import { Navigate, useLocation } from 'react-router';
import { Alert, Button, Card, Input, Label, TextField } from '@heroui/react';
import { z } from 'zod';
import { useAuth } from './auth-context';

const loginSchema = z.object({
  login: z.string().min(1, 'Введите логин'),
  password: z.string().min(1, 'Введите пароль'),
});

export function LoginPage() {
  const { isAuthenticated, loginWithCredentials } = useAuth();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (isAuthenticated) {
    return <Navigate to={from} replace />;
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    const parsed = loginSchema.safeParse({ login, password });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? 'Некорректные данные');
      return;
    }

    setSubmitting(true);
    try {
      await loginWithCredentials(parsed.data.login, parsed.data.password);
    } catch {
      setError('Неверный логин или пароль');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <Card className="w-full max-w-md">
        <Card.Header>
          <Card.Title>Transora Admin</Card.Title>
          <Card.Description>Вход в панель управления</Card.Description>
        </Card.Header>
        <Card.Content>
          <form className="space-y-4" onSubmit={handleSubmit}>
            <TextField isRequired name="login" value={login} onChange={setLogin}>
              <Label>Логин</Label>
              <Input autoComplete="username" />
            </TextField>
            <TextField isRequired name="password" type="password" value={password} onChange={setPassword}>
              <Label>Пароль</Label>
              <Input autoComplete="current-password" type="password" />
            </TextField>

            {error ? (
              <Alert status="danger">
                <Alert.Indicator />
                <Alert.Content>
                  <Alert.Description>{error}</Alert.Description>
                </Alert.Content>
              </Alert>
            ) : null}

            <Button className="w-full" type="submit" variant="primary" isPending={submitting}>
              Войти
            </Button>
          </form>
        </Card.Content>
      </Card>
    </div>
  );
}
