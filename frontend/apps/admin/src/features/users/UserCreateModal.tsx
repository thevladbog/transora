import { useState } from 'react';
import {
  Alert,
  Button,
  ListBox,
  Modal,
  useOverlayState,
} from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import { CreateUserRequestUserType } from '@transora/api-client';
import { FormSelectField, FormTextField } from '@/components/ui/FormFields';
import { useCreateUser } from './api/hooks';

type UserCreateModalProps = {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated?: (userId: string) => void;
};

export function UserCreateModal({ isOpen, onOpenChange, onCreated }: UserCreateModalProps) {
  const { t } = useTranslation(['users', 'common']);
  const createUser = useCreateUser();
  const modalState = useOverlayState({ isOpen, onOpenChange });

  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [userType, setUserType] = useState<string>(CreateUserRequestUserType.USER);
  const [error, setError] = useState<string | null>(null);

  const schema = z.object({
    login: z.string().min(1, t('users:loginRequired')),
    password: z.string().min(1, t('users:passwordRequired')),
    fullName: z.string().min(1, t('users:fullNameRequired')),
    email: z.string().optional(),
    userType: z.enum([CreateUserRequestUserType.USER, CreateUserRequestUserType.SERVICE]),
  });

  function resetForm() {
    setLogin('');
    setPassword('');
    setFullName('');
    setEmail('');
    setUserType(CreateUserRequestUserType.USER);
    setError(null);
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    const parsed = schema.safeParse({ login, password, fullName, email: email || undefined, userType });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? t('common:errorGeneric'));
      return;
    }

    try {
      const created = await createUser.mutateAsync({
        login: parsed.data.login,
        password: parsed.data.password,
        fullName: parsed.data.fullName,
        email: parsed.data.email,
        userType: parsed.data.userType,
      });
      resetForm();
      modalState.close();
      onCreated?.(created.userId);
    } catch {
      setError(t('users:createError'));
    }
  }

  return (
    <Modal state={modalState}>
      <Modal.Backdrop>
        <Modal.Container size="md">
          <Modal.Dialog>
            <Modal.Header>
              <Modal.Heading>{t('users:createTitle')}</Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <p className="mb-4 text-sm text-muted">{t('users:createDescription')}</p>
              <form className="transora-form-stack" id="create-user-form" onSubmit={handleSubmit}>
                <FormTextField
                  isRequired
                  label={t('users:login')}
                  name="login"
                  value={login}
                  onChange={setLogin}
                  inputProps={{ className: 'font-mono', autoComplete: 'username' }}
                />
                <FormTextField
                  isRequired
                  label={t('users:password')}
                  name="password"
                  type="password"
                  value={password}
                  onChange={setPassword}
                  inputProps={{ type: 'password', autoComplete: 'new-password' }}
                />
                <FormTextField
                  isRequired
                  label={t('users:fullName')}
                  name="fullName"
                  value={fullName}
                  onChange={setFullName}
                />
                <FormTextField
                  label={t('users:email')}
                  name="email"
                  value={email}
                  onChange={setEmail}
                  inputProps={{ type: 'email', autoComplete: 'email' }}
                />
                <FormSelectField
                  label={t('users:userType')}
                  selectedKey={userType}
                  onSelectionChange={(key) => setUserType(String(key))}
                >
                  <ListBox.Item id={CreateUserRequestUserType.USER} textValue={t('users:userTypeUser')}>
                    {t('users:userTypeUser')}
                  </ListBox.Item>
                  <ListBox.Item id={CreateUserRequestUserType.SERVICE} textValue={t('users:userTypeService')}>
                    {t('users:userTypeService')}
                  </ListBox.Item>
                </FormSelectField>
                {error ? (
                  <Alert status="danger">
                    <Alert.Indicator />
                    <Alert.Content>
                      <Alert.Description>{error}</Alert.Description>
                    </Alert.Content>
                  </Alert>
                ) : null}
              </form>
            </Modal.Body>
            <Modal.Footer>
              <Button variant="secondary" onPress={() => modalState.close()}>
                {t('common:cancel')}
              </Button>
              <Button
                form="create-user-form"
                type="submit"
                variant="primary"
                isPending={createUser.isPending}
              >
                {t('common:create')}
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </Modal>
  );
}
