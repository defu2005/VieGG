package com.rentgg.catalog.domain;

/**
 * How likely renting an account of this game ends in damage (bans, stolen items, recovery abuse).
 * Shown to owners when they price the deposit, and to renters on the listing page.
 */
public enum GameRiskLevel { LOW, MEDIUM, HIGH, VERY_HIGH }
