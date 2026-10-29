import { formatVnd } from './format';

/**
 * Platform fee on every risk payout (deposit deduction to the owner, compensation to the renter).
 * The payer pays the full amount; VieGG keeps this share and the recipient gets the rest.
 * Must match backend `RiskPayoutFee.BPS`.
 */
export const RISK_FEE_PERCENT = 10;

export function riskFee(amount: number): number {
  return Math.floor((amount * RISK_FEE_PERCENT) / 100);
}

export function riskNet(amount: number): number {
  return amount - riskFee(amount);
}

/** "nhận 45.000 đ sau phí 10%" */
export function netText(amount: number): string {
  return `nhận ${formatVnd(riskNet(amount))} sau phí ${RISK_FEE_PERCENT}%`;
}

export const RISK_FEE_NOTICE = `VieGG thu phí ${RISK_FEE_PERCENT}% trên mọi khoản đền bù rủi ro (khấu trừ cọc trả cho chủ account, đền bù trả cho người thuê): người trả trả đủ, người nhận được ${100 - RISK_FEE_PERCENT}%.`;
