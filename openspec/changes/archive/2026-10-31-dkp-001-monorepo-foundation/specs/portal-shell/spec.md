# Portal Shell

## Purpose

Provide an accessible administration entry point and verifiable navigation without introducing unimplemented document or knowledge workflows.

## ADDED Requirements

### Requirement: Functional navigation
The portal SHALL provide a responsive application shell with a home route and a foundation-information route, semantic landmarks, a visible active navigation state, keyboard navigation and a not-found view. It SHALL use React, Vite and strict TypeScript in the pnpm workspace.

#### Scenario: Navigate and reload
- **WHEN** a user opens home, navigates to foundation information, and reloads that route
- **THEN** the correct view and active link remain visible without an error

#### Scenario: Keyboard and unknown route
- **WHEN** a keyboard user follows navigation and then opens an unknown route
- **THEN** navigation has a visible focus state and the not-found view provides a working home link

### Requirement: Honest foundation state
The portal SHALL identify itself as a foundation shell and SHALL offer no upload, search, publication, permission or plugin actions. It SHALL neither fabricate business results nor access persistence directly. No authentication token, credential or environment secret SHALL be embedded in the browser bundle.

#### Scenario: Inspect available actions
- **WHEN** a user opens any shell view
- **THEN** only navigation and shell presentation controls are available, with no simulated business success or requests to a data store

### Requirement: Theme preference
The shell SHALL default to a dark theme and provide a light theme option with accessible control labeling and readable content in both modes.

#### Scenario: Switch theme
- **WHEN** the user selects the light theme using the keyboard
- **THEN** the theme changes and navigation and content remain operable
