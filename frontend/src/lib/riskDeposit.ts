/**
 * Order deposit from per-risk deposits (same rule as ListingService.applyRiskTerms on the backend):
 * stackable risks can happen together, so their deposits add up; an exclusive risk (e.g. permanent ban) replaces
 * the others, so only the largest one counts. Deposit = max(sum of stackable, largest exclusive).
 */
export interface RiskDepositPart {
  amount: number;
  stackable: boolean;
}

export interface RiskDepositBreakdown {
  stackedSum: number;
  exclusiveMax: number;
  deposit: number;
  /** Which side sets the deposit; 'none' when nothing is entered yet. */
  drivenBy: 'stacked' | 'exclusive' | 'none';
}

export function riskDeposit(parts: RiskDepositPart[]): RiskDepositBreakdown {
  let stackedSum = 0;
  let exclusiveMax = 0;
  for (const p of parts) {
    const a = Number.isFinite(p.amount) && p.amount > 0 ? p.amount : 0;
    if (p.stackable) stackedSum += a;
    else exclusiveMax = Math.max(exclusiveMax, a);
  }
  const deposit = Math.max(stackedSum, exclusiveMax);
  const drivenBy = deposit === 0 ? 'none' : exclusiveMax > stackedSum ? 'exclusive' : 'stacked';
  return { stackedSum, exclusiveMax, deposit, drivenBy };
}

export const STACKABLE_LABEL = 'Cộng dồn';
export const EXCLUSIVE_LABEL = 'Không cộng dồn';
