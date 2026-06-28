# Werkbank

Bring localhost to everywhere. Static domains, SSL and access control.

## Why Werkbank?

Ever written an app with a custom backend? Especially nowadays, we develop both at the same time.
We see backends and apps evolve side-by-side. Not having a mock server is common if you're
working on a side-project or a small team, so you start experimenting with VPNs, DNS and `10.0.2.2` in the
Android emulator. And then, CORS issues arise in your app. Also, what about webhooks? They NEED an externaly accessible
server.

Werkbank makes it easy to bring localhost to the web. Create a Werkbankfile, add your services and run
`wb setup`, which creates domains, SSL-certificates. Locally, no cloud _required_. Trust the generated certificate
and connect to `myproject.werkbank.space`. Want to share it with your team? Create a free account at https://wbspace.app, you
get a domain and a wildcard SSL certificate. Run `wb login` and `wb setup` again to link the project to your account.
With `wb tunnel` the proxy is active and you can access your services from anywhere.

## Installation

Download the latest release from https://github.com/Julius-Babies/werkbank/releases and add it to your PATH.
Note that the wb-binary is currently only supported on macOS; Linux is still in development. Windows will never be
supported on its own for now, you'll have to use WSL.