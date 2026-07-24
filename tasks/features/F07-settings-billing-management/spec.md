# Technical Specification: F07-settings-billing-management

## 1. Technical Overview
- **Feature Name**: F07 - Settings Billing Management
- **Tech Stack**: Flutter (Riverpod), Go (Fiber, GORM).
- **Architecture Approach**: Protected tab in **Settings > Assinatura** for Clinic Owners to view subscription details, apply promo codes, and upgrade/change plans.

## 2. Component Architecture
- `BillingSettingsTab`: Displays current status (`trialing`, `active`, `grace_period`), renewal date, active plan card, and promo code form.
- `ChangePlanDialog`: Modal popup displaying plan matrix and instant upgrade buttons.

## 3. Core Logic & Algorithms
- `POST /saas/apply-coupon`: Validates coupon code and attaches to the clinic's active subscription.
- `POST /saas/change-plan`: Updates subscription tier directly or creates a new billing session.
