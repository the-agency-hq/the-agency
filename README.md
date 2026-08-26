# the-agency

The Agency web application that creates the Briefs that are then passed to the Handlers.

## License and deployment notice

This project is currently open source under the MIT license. However, it is not currently design to be easily run locally. We'll consider supporting that type of deployment at some point in the future though.

## Local FusionAuth

This project currently requires a licensed instance of FusionAuth as the Identity Provider. If you are part of the team, contact Brian for a FusionAuth developer license key.

To run FusionAuth locally, follow these steps:

```shell
cd src/main/fusionauth
cp .env.template .env
```

Next, edit the `.env` file and add your license key.

Then run FusionAuth in Docker:

```shell
docker compose up -d
```

FusionAuth is accessible at http://localhost:9016 and is preconfigured for the web application and the Handler CLI. You can test with these credentials:

* Admin user: admin@theagencyhq.dev/password
* Ordinary user: user@theagencyhq.dev/password

If you need any emails from FusionAuth, the Docker compose runs Mailcatcher at http://localhost:1080

### Latte framework

This project uses the Latte Web framework and HTTP server. You can learn more about those at https://lattejava.org. 

## Concepts

* Brief - the collection of rules, commands, skills, and other files used by Agents (LLMs)
* Organization - contains a single connected GitHub repository that is the source of the Brief
* Member - a user that is part of an Organization

## Briefs from GitHub

Users connect a GitHub repository to an Organization. This app polls GitHub for changes to the repository. Any changes that occur are downloaded and translated into a Brief. This is stored in the database and versioned.

### GitHub App

Connecting a repository goes through a GitHub App (not an OAuth App). Register one per environment — its callback and setup URLs are single values, so a development App points at `http://localhost:8080` — and configure it as follows:

| Setting | Value |
|---------|-------|
| Callback URL | `<base URL>/app/oauth/github/callback` |
| Expire user authorization tokens | On |
| Request user authorization (OAuth) during installation | Off (with it on, GitHub ignores the Setup URL and sends installs to the callback URL, which also returns to the picker) |
| Setup URL | `<base URL>/app/oauth/github/setup` |
| Redirect on update | On |
| Repository permissions | Contents: read, Metadata: read |
| Webhook | Off (not used) |

Then put the App's slug and OAuth credentials in `~/.config/the-agency-hq/the-agency/config.properties`:

```properties
github.appName=<slug from https://github.com/apps/<slug>>
github.clientId=...
github.clientSecret=...
```

Connecting a repository is then: create an Organization, connect GitHub from its page (the OAuth authorization), and pick a repository. The picker lists every repository the App is installed on that your GitHub user can see. Its install links send you to GitHub to install the App on another account or change what an installation covers, and GitHub returns you to the picker, which lists again.

## Building and testing

This project uses Latte's CLI as the build and project management system. Here are some commands:

| Command               | Description                                                    |
|-----------------------|----------------------------------------------------------------|
| `latte build`         | Builds the project                                             |
| `latte main-database` | Creates the project's main database in a local Postgres server |
| `latte test-database` | Creates the project's test database in a local Postgres server |
| `latte test`          | Runs the tests                                                 |
| `latte run`           | Runs the webapp locally                                        |
| `latte deploy`        | Deploys the webapp to Railway                                  |

# Contributing

If you would like to contribute to this project, contact Brian Pontarelli (@voidamin).