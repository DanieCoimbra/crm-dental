# Technical Specification: F08-grace-period-and-webhooks

## 1. Technical Overview
- **Feature Name**: F08 - Grace Period & Webhooks
- **Tech Stack**: Go (Fiber, GORM, Stripe Webhook SDK), Flutter (Riverpod).
- **Architecture Approach**: 3-day Grace Period enforcement on failed payments or trial expiry, warning banner widget on topbar, and real-time processing of Stripe events.

## 2. Data Models & Schema
- `Subscription.GracePeriodEndsAt`: `*time.Time` field on `subscriptions` table.

## 3. Component Architecture
- `saas_middleware.go`: Evaluates subscription status and grace period expiration timestamp.
- `WebhookHandler.HandleStripe`: Listens to `checkout.session.completed`, `customer.subscription.updated`, and `invoice.payment_failed`.
- `GracePeriodBanner`: Flutter widget mounted on `TopBar` displaying countdown message.

## 4. Core Logic & Algorithms
- Upon `invoice.payment_failed` or Trial expiration:
  - `Subscription.Status = "grace_period"`
  - `Subscription.GracePeriodEndsAt = NOW() + 3 days`.
- Middleware logic:
  - `NOW() <= GracePeriodEndsAt`: Route allowed, `in_grace_period` flag injected in responses.
  - `NOW() > GracePeriodEndsAt`: Route blocked with HTTP 402 `Payment Required`.
