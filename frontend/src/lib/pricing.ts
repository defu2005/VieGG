import type { Listing, PricingTier } from '../api/types';

/** Durations an owner or staff can pick for a rental package. */
export const PACKAGE_DURATIONS = [30, 60, 120, 180, 300, 720, 1440, 2880, 4320, 10080];

/** Durations offered when a listing has no packages (priced per hour). */
const HOURLY_DURATIONS = [60, 120, 180, 300, 720, 1440];

export function durationLabel(minutes: number): string {
  if (minutes % 1440 === 0) return `${minutes / 1440} ngày`;
  if (minutes % 60 === 0) return `${minutes / 60} giờ`;
  if (minutes > 60) return `${Math.floor(minutes / 60)} giờ ${minutes % 60} phút`;
  return `${minutes} phút`;
}

/** Free-cancel windows an owner can pick (minutes before the start; 0 = until the start). */
export const FREE_CANCEL_CHOICES = [0, 30, 60, 120, 360, 720, 1440, 2880, 4320, 10080];

/** "Hủy miễn phí đến trước giờ bắt đầu 2 giờ" style wording for a free-cancel window. */
export function freeCancelLabel(minutes: number): string {
  return minutes <= 0 ? 'đến ngay trước giờ bắt đầu' : `nếu hủy trước giờ bắt đầu ít nhất ${durationLabel(minutes)}`;
}

/** What the renter can pick: the listing's packages, or hourly-priced durations within the game's limits. */
export function rentOptions(l: Listing, minMinutes: number, maxMinutes: number): PricingTier[] {
  if (l.pricingTiers?.length) return l.pricingTiers;
  return HOURLY_DURATIONS.filter((d) => d >= minMinutes && d <= maxMinutes).map((d) => ({
    durationMinutes: d,
    price: Math.floor((l.pricePerHour * d) / 60),
  }));
}

/** Short price line for cards: "từ 25.000 ₫ / 1 giờ" for packages, "30.000 ₫ / giờ" otherwise. */
export function priceSummary(l: Listing): { amount: number; unit: string; from: boolean } {
  const tiers = l.pricingTiers ?? [];
  if (tiers.length === 0) return { amount: l.pricePerHour, unit: 'giờ', from: false };
  const first = tiers[0];
  return { amount: first.price, unit: durationLabel(first.durationMinutes), from: tiers.length > 1 };
}
