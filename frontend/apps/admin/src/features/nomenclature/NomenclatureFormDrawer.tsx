import { useEffect, useState } from 'react';
import { Alert, Label, ListBox, Switch } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import type { NomenclatureResponse } from '@transora/api-client';
import { FormDrawer } from '@/components/ui/FormDrawer';
import { FormSelectField, FormTextField } from '@/components/ui/FormFields';
import { usePoliciesList } from '@/features/refund-policies/api/hooks';
import { useCreateNomenclature, useUpdateNomenclature } from './api/hooks';

const CATEGORIES = ['BAGGAGE', 'INSURANCE', 'SERVICE', 'OTHER'] as const;
const SALE_MODES = ['STANDALONE', 'TICKET_ATTACHED'] as const;
const PRICING_MODES = ['FIXED', 'PERCENT_OF_ROUTE'] as const;

const FFD_PAYMENT_OBJECTS = [
  { id: '1', labelKey: 'ffdPaymentObject.commodity' },
  { id: '4', labelKey: 'ffdPaymentObject.service' },
  { id: '10', labelKey: 'ffdPaymentObject.payment' },
  { id: '12', labelKey: 'ffdPaymentObject.agentCommission' },
] as const;

const FFD_PAYMENT_METHODS = [
  { id: '4', labelKey: 'ffdPaymentMethod.fullPayment' },
  { id: '1', labelKey: 'ffdPaymentMethod.fullPrepayment' },
  { id: '3', labelKey: 'ffdPaymentMethod.advance' },
] as const;

const FFD_VAT_TAGS = [
  { id: '11', labelKey: 'ffdVat.vat22' },
  { id: '12', labelKey: 'ffdVat.vat22_122' },
  { id: '6', labelKey: 'ffdVat.none' },
  { id: '2', labelKey: 'ffdVat.vat10' },
  { id: '4', labelKey: 'ffdVat.vat10_110' },
  { id: '1', labelKey: 'ffdVat.vat20' },
  { id: '3', labelKey: 'ffdVat.vat20_120' },
  { id: '5', labelKey: 'ffdVat.vat0' },
  { id: '7', labelKey: 'ffdVat.vat5' },
  { id: '8', labelKey: 'ffdVat.vat7' },
] as const;

const FFD_MEASURE_CODES = [
  { id: '0', labelKey: 'ffdMeasure.notApplicable' },
  { id: '796', labelKey: 'ffdMeasure.piece' },
] as const;

type NomenclatureFormDrawerProps = {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  item?: NomenclatureResponse | null;
};

function categoryFiscalPreset(category: string) {
  switch (category) {
    case 'INSURANCE':
      return { ffdPaymentObject: 4, ffdPaymentMethod: 4, ffdVatTag: 6, ffdMeasureCode: 0 };
    case 'BAGGAGE':
      return { ffdPaymentObject: 4, ffdPaymentMethod: 4, ffdVatTag: 11, ffdMeasureCode: 796 };
    case 'SERVICE':
      return { ffdPaymentObject: 4, ffdPaymentMethod: 4, ffdVatTag: 11, ffdMeasureCode: 796 };
    default:
      return { ffdPaymentObject: 4, ffdPaymentMethod: 4, ffdVatTag: 6, ffdMeasureCode: 0 };
  }
}

export function NomenclatureFormDrawer({ isOpen, onOpenChange, item }: NomenclatureFormDrawerProps) {
  const { t } = useTranslation(['nomenclature', 'common']);
  const isEdit = Boolean(item);
  const createItem = useCreateNomenclature();
  const updateItem = useUpdateNomenclature();
  const { data: policies } = usePoliciesList('REFUND');

  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [category, setCategory] = useState<string>('BAGGAGE');
  const [priceCents, setPriceCents] = useState('0');
  const [description, setDescription] = useState('');
  const [isActive, setIsActive] = useState(true);
  const [saleMode, setSaleMode] = useState<string>('STANDALONE');
  const [pricingMode, setPricingMode] = useState<string>('FIXED');
  const [routePercent, setRoutePercent] = useState('');
  const [minPriceCents, setMinPriceCents] = useState('');
  const [maxPriceCents, setMaxPriceCents] = useState('');
  const [maxQtyPerTicket, setMaxQtyPerTicket] = useState('1');
  const [refundAllowed, setRefundAllowed] = useState(false);
  const [refundPolicyId, setRefundPolicyId] = useState<string>('');
  const [printName, setPrintName] = useState('');
  const [ffdPaymentObject, setFfdPaymentObject] = useState('4');
  const [ffdPaymentMethod, setFfdPaymentMethod] = useState('4');
  const [ffdVatTag, setFfdVatTag] = useState('6');
  const [ffdMeasureCode, setFfdMeasureCode] = useState('0');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    if (item) {
      setCode(item.code);
      setName(item.name);
      setCategory(item.category);
      setPriceCents(String(item.priceCents));
      setDescription(item.description ?? '');
      setIsActive(item.isActive);
      setSaleMode(item.saleMode);
      setPricingMode(item.pricingMode);
      setRoutePercent(item.routePercent != null ? String(item.routePercent) : '');
      setMinPriceCents(item.minPriceCents != null ? String(item.minPriceCents) : '');
      setMaxPriceCents(item.maxPriceCents != null ? String(item.maxPriceCents) : '');
      setMaxQtyPerTicket(String(item.maxQtyPerTicket));
      setRefundAllowed(item.refundAllowed);
      setRefundPolicyId(item.refundPolicyId ?? '');
      setPrintName(item.printName);
      setFfdPaymentObject(String(item.ffdPaymentObject));
      setFfdPaymentMethod(String(item.ffdPaymentMethod));
      setFfdVatTag(String(item.ffdVatTag));
      setFfdMeasureCode(String(item.ffdMeasureCode));
    } else {
      setCode('');
      setName('');
      setCategory('BAGGAGE');
      setPriceCents('0');
      setDescription('');
      setIsActive(true);
      setSaleMode('STANDALONE');
      setPricingMode('FIXED');
      setRoutePercent('');
      setMinPriceCents('');
      setMaxPriceCents('');
      setMaxQtyPerTicket('1');
      setRefundAllowed(false);
      setRefundPolicyId('');
      setPrintName('');
      const preset = categoryFiscalPreset('BAGGAGE');
      setFfdPaymentObject(String(preset.ffdPaymentObject));
      setFfdPaymentMethod(String(preset.ffdPaymentMethod));
      setFfdVatTag(String(preset.ffdVatTag));
      setFfdMeasureCode(String(preset.ffdMeasureCode));
    }
    setError(null);
  }, [isOpen, item]);

  useEffect(() => {
    if (isEdit || !isOpen) return;
    const preset = categoryFiscalPreset(category);
    setFfdPaymentObject(String(preset.ffdPaymentObject));
    setFfdPaymentMethod(String(preset.ffdPaymentMethod));
    setFfdVatTag(String(preset.ffdVatTag));
    setFfdMeasureCode(String(preset.ffdMeasureCode));
  }, [category, isEdit, isOpen]);

  useEffect(() => {
    if (saleMode === 'STANDALONE' && pricingMode !== 'FIXED') {
      setPricingMode('FIXED');
    }
  }, [saleMode, pricingMode]);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    const schema = z.object({
      code: z.string().min(1),
      name: z.string().min(1),
      category: z.string().min(1),
      priceCents: z.coerce.number().min(0),
      isActive: z.boolean(),
      saleMode: z.string().min(1),
      pricingMode: z.string().min(1),
      maxQtyPerTicket: z.coerce.number().min(1),
      refundAllowed: z.boolean(),
      printName: z.string().min(1),
      ffdPaymentObject: z.coerce.number(),
      ffdPaymentMethod: z.coerce.number(),
      ffdVatTag: z.coerce.number(),
      ffdMeasureCode: z.coerce.number(),
    });
    const parsed = schema.safeParse({
      code,
      name,
      category,
      priceCents,
      isActive,
      saleMode,
      pricingMode,
      maxQtyPerTicket,
      refundAllowed,
      printName: printName || name,
      ffdPaymentObject,
      ffdPaymentMethod,
      ffdVatTag,
      ffdMeasureCode,
    });
    if (!parsed.success) {
      setError(t('nomenclature:validationError'));
      return;
    }
    if (parsed.data.refundAllowed && !refundPolicyId) {
      setError(t('nomenclature:refundPolicyRequired'));
      return;
    }
    const payload = {
      ...parsed.data,
      printName: printName || name,
      routePercent: routePercent ? Number(routePercent) : undefined,
      minPriceCents: minPriceCents ? Number(minPriceCents) : undefined,
      maxPriceCents: maxPriceCents ? Number(maxPriceCents) : undefined,
      refundPolicyId: refundAllowed ? refundPolicyId : undefined,
      description: description || undefined,
    };
    try {
      if (isEdit && item) {
        await updateItem.mutateAsync({ id: item.id, payload });
      } else {
        await createItem.mutateAsync(payload);
      }
      onOpenChange(false);
    } catch {
      setError(t('nomenclature:saveError'));
    }
  }

  return (
    <FormDrawer
      isOpen={isOpen}
      onOpenChange={onOpenChange}
      title={isEdit ? t('nomenclature:editTitle') : t('nomenclature:createTitle')}
      formId="nomenclature-form"
      isPending={createItem.isPending || updateItem.isPending}
    >
      <form className="transora-form-stack" id="nomenclature-form" onSubmit={handleSubmit}>
        <h3 className="text-sm font-semibold text-foreground">{t('nomenclature:sectionBasic')}</h3>
        <FormTextField isRequired label={t('nomenclature:code')} name="code" value={code} onChange={setCode} />
        <FormTextField isRequired label={t('nomenclature:name')} name="name" value={name} onChange={setName} />
        <FormSelectField
          label={t('nomenclature:category')}
          name="category"
          selectedKey={category}
          onSelectionChange={(key) => setCategory(String(key))}
        >
          {CATEGORIES.map((cat) => (
            <ListBox.Item key={cat} id={cat} textValue={t(`nomenclature:categories.${cat}`)}>
              {t(`nomenclature:categories.${cat}`)}
            </ListBox.Item>
          ))}
        </FormSelectField>
        <FormTextField label={t('nomenclature:descriptionField')} name="description" value={description} onChange={setDescription} />
        <div className="transora-form-field flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
          <Label>{t('nomenclature:isActive')}</Label>
          <Switch isSelected={isActive} onChange={setIsActive}>
            <Switch.Control><Switch.Thumb /></Switch.Control>
          </Switch>
        </div>

        <h3 className="text-sm font-semibold text-foreground">{t('nomenclature:sectionSale')}</h3>
        <FormSelectField
          label={t('nomenclature:saleMode')}
          name="saleMode"
          selectedKey={saleMode}
          onSelectionChange={(key) => setSaleMode(String(key))}
        >
          {SALE_MODES.map((mode) => (
            <ListBox.Item key={mode} id={mode} textValue={t(`nomenclature:saleModes.${mode}`)}>
              {t(`nomenclature:saleModes.${mode}`)}
            </ListBox.Item>
          ))}
        </FormSelectField>
        <FormSelectField
          label={t('nomenclature:pricingMode')}
          name="pricingMode"
          selectedKey={pricingMode}
          onSelectionChange={(key) => setPricingMode(String(key))}
        >
          {PRICING_MODES.filter((mode) => saleMode === 'TICKET_ATTACHED' || mode === 'FIXED').map((mode) => (
            <ListBox.Item key={mode} id={mode} textValue={t(`nomenclature:pricingModes.${mode}`)}>
              {t(`nomenclature:pricingModes.${mode}`)}
            </ListBox.Item>
          ))}
        </FormSelectField>
        {pricingMode === 'FIXED' ? (
          <FormTextField
            isRequired
            label={t('nomenclature:priceCents')}
            name="priceCents"
            type="number"
            value={priceCents}
            onChange={setPriceCents}
            inputProps={{ type: 'number', min: 0 }}
          />
        ) : (
          <>
            <FormTextField
              isRequired
              label={t('nomenclature:routePercent')}
              name="routePercent"
              value={routePercent}
              onChange={setRoutePercent}
              inputProps={{ type: 'number', min: 0, max: 100, step: 0.01 }}
            />
            <FormTextField label={t('nomenclature:minPriceCents')} name="minPriceCents" value={minPriceCents} onChange={setMinPriceCents} inputProps={{ type: 'number', min: 0 }} />
            <FormTextField label={t('nomenclature:maxPriceCents')} name="maxPriceCents" value={maxPriceCents} onChange={setMaxPriceCents} inputProps={{ type: 'number', min: 0 }} />
          </>
        )}
        {saleMode === 'TICKET_ATTACHED' ? (
          <FormTextField
            isRequired
            label={t('nomenclature:maxQtyPerTicket')}
            name="maxQtyPerTicket"
            value={maxQtyPerTicket}
            onChange={setMaxQtyPerTicket}
            inputProps={{ type: 'number', min: 1 }}
          />
        ) : null}

        <h3 className="text-sm font-semibold text-foreground">{t('nomenclature:sectionRefund')}</h3>
        <div className="transora-form-field flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
          <Label>{t('nomenclature:refundAllowed')}</Label>
          <Switch isSelected={refundAllowed} onChange={setRefundAllowed}>
            <Switch.Control><Switch.Thumb /></Switch.Control>
          </Switch>
        </div>
        <FormSelectField
          label={t('nomenclature:refundPolicy')}
          name="refundPolicyId"
          selectedKey={refundPolicyId || null}
          isDisabled={!refundAllowed}
          onSelectionChange={(key) => setRefundPolicyId(key ? String(key) : '')}
        >
          {(policies ?? []).map((policy) => (
            <ListBox.Item key={policy.id} id={policy.id} textValue={policy.name}>
              {policy.name}
            </ListBox.Item>
          ))}
        </FormSelectField>

        <h3 className="text-sm font-semibold text-foreground">{t('nomenclature:sectionFiscal')}</h3>
        <FormTextField
          isRequired
          label={t('nomenclature:printName')}
          name="printName"
          value={printName}
          onChange={setPrintName}
        />
        <FormSelectField
          label={t('nomenclature:ffdPaymentObject')}
          name="ffdPaymentObject"
          selectedKey={ffdPaymentObject}
          onSelectionChange={(key) => setFfdPaymentObject(String(key))}
        >
          {FFD_PAYMENT_OBJECTS.map((opt) => (
            <ListBox.Item key={opt.id} id={opt.id} textValue={t(`nomenclature:${opt.labelKey}`)}>
              {t(`nomenclature:${opt.labelKey}`)}
            </ListBox.Item>
          ))}
        </FormSelectField>
        <FormSelectField
          label={t('nomenclature:ffdPaymentMethod')}
          name="ffdPaymentMethod"
          selectedKey={ffdPaymentMethod}
          onSelectionChange={(key) => setFfdPaymentMethod(String(key))}
        >
          {FFD_PAYMENT_METHODS.map((opt) => (
            <ListBox.Item key={opt.id} id={opt.id} textValue={t(`nomenclature:${opt.labelKey}`)}>
              {t(`nomenclature:${opt.labelKey}`)}
            </ListBox.Item>
          ))}
        </FormSelectField>
        <FormSelectField
          label={t('nomenclature:ffdVatTag')}
          name="ffdVatTag"
          selectedKey={ffdVatTag}
          onSelectionChange={(key) => setFfdVatTag(String(key))}
        >
          {FFD_VAT_TAGS.map((opt) => (
            <ListBox.Item key={opt.id} id={opt.id} textValue={t(`nomenclature:${opt.labelKey}`)}>
              {t(`nomenclature:${opt.labelKey}`)}
            </ListBox.Item>
          ))}
        </FormSelectField>
        <FormSelectField
          label={t('nomenclature:ffdMeasureCode')}
          name="ffdMeasureCode"
          selectedKey={ffdMeasureCode}
          onSelectionChange={(key) => setFfdMeasureCode(String(key))}
        >
          {FFD_MEASURE_CODES.map((opt) => (
            <ListBox.Item key={opt.id} id={opt.id} textValue={t(`nomenclature:${opt.labelKey}`)}>
              {t(`nomenclature:${opt.labelKey}`)}
            </ListBox.Item>
          ))}
        </FormSelectField>

        {error ? (
          <Alert status="danger">
            <Alert.Indicator />
            <Alert.Content><Alert.Description>{error}</Alert.Description></Alert.Content>
          </Alert>
        ) : null}
      </form>
    </FormDrawer>
  );
}
