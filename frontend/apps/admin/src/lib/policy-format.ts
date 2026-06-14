import type { CommercePolicyResponse } from '@transora/api-client';
import type { TFunction } from 'i18next';
import { formatMoney } from './format';

export function formatPolicyPrice(
  policy: CommercePolicyResponse,
  locale: string,
  t: TFunction<'refundPolicies'>,
): string {
  switch (policy.pricingMode) {
    case 'FIXED':
      return policy.fixedPriceCents != null
        ? formatMoney(policy.fixedPriceCents, locale)
        : '—';
    case 'PERCENT': {
      if (policy.percentValue == null) return '—';
      const basis = policy.percentBasis
        ? t(`percentBasis_${policy.percentBasis}`)
        : '';
      return basis ? `${policy.percentValue}% (${basis})` : `${policy.percentValue}%`;
    }
    case 'FROM_NOMENCLATURE':
    default:
      return t('priceFromNomenclature');
  }
}

export function formatPolicyMandatory(
  policy: CommercePolicyResponse,
  t: TFunction<'refundPolicies'>,
): string {
  if (policy.policyType !== 'SALE') return '—';
  return policy.isMandatory ? t('mandatoryYes') : t('mandatoryNo');
}
