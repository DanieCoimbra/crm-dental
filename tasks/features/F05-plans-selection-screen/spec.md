# Technical Specification: F05-plans-selection-screen

## 1. Technical Overview
- **Feature Name**: F05 - Plans Selection Screen
- **Tech Stack**: Flutter (Riverpod, GoRouter, Lucide Icons).
- **Architecture Approach**: Public UI screen accessible at `/plans` presenting a pricing matrix (Start, Pro, Enterprise tiers + 14-day Trial card) with an interactive billing cycle toggle (Mensal / Anual).

## 2. Data Models & Schema
```dart
class PlanTier {
  final String id;
  final String title;
  final String description;
  final double monthlyPrice;
  final double annualPrice;
  final List<String> features;
  final bool isPopular;

  const PlanTier({
    required this.id,
    required this.title,
    required this.description,
    required this.monthlyPrice,
    required this.annualPrice,
    required this.features,
    this.isPopular = false,
  });
}
```

## 3. Component Architecture
- `PlansScreen`: Main screen widget.
- `BillingCycleToggle`: Toggle widget between Mensal (Monthly) and Anual (Annual - 20% discount).
- `PlanCardWidget`: Card widget for each tier showing price calculation, feature checklist, and CTA.
- `TrialCardWidget`: Highlighted 14-day free trial card with zero-risk CTA.

## 4. Core Logic & Algorithms
- Annual billing applies a 20% discount calculation dynamically: `annualPrice * 12`.
- Clicking "Modo Trial" routes to `/register-clinic?plan=trial`.
- Clicking "Assinar [Plano]" routes to pre-register Stripe Checkout (F06).

## 5. Error Handling & Edge Cases
- Handles deep links or fallback routes if plan parameters are malformed.
