# OP_RETURN Bot

[![Build Status](https://github.com/benthecarman/OP-RETURN-Bot/workflows/Compile%20&%20Formatting/badge.svg)](https://github.com/benthecarman/OP-RETURN-Bot/actions)

## Building from source

To get started you will need Java, Scala, and some other nice tools installed, luckily the Scala team has an easy setup process!

Simply follow the instructions in [this short blog](https://www.scala-lang.org/2020/06/29/one-click-install.html) to get started.

After having these installed you simply need to clone the repo, then run with `sbt run`.

And open [http://localhost:9000/](http://localhost:9000/)

## Deploy with NixOS

The flake provides an `op-return-bot` package and a NixOS module. The module
runs the bot as a restricted system service. It stores state in
`/var/lib/op-return-bot` by default.

The flake pins the source commits for the two snapshot dependencies. The Nix
build compiles these dependencies from source and does not use Sonatype.

Add the flake to the inputs of your NixOS flake:

```nix
{
  inputs.op-return-bot.url =
    "github:benthecarman/OP-RETURN-Bot";

  outputs = { nixpkgs, op-return-bot, ... }: {
    nixosConfigurations.server = nixpkgs.lib.nixosSystem {
      system = "x86_64-linux";
      modules = [
        op-return-bot.nixosModules.default
        {
          services.op-return-bot = {
            enable = true;
            address = "127.0.0.1";
            port = 9000;
            configFile = "/run/secrets/op-return-bot.conf";
          };
        }
      ];
    };
  };
}
```

Put the application configuration at the path in `configFile`. Use a secret
manager such as `agenix` or `sops-nix` to create this file during startup.
Do not add credentials to the Nix store.

Make sure that the `op-return-bot` user can read each secret file. Keep write
access limited to the account that manages the secrets.

The configuration file must contain the Bitcoin Core and LND connection
values. It can also contain the Twitter, Telegram, and Nostr values.

```hocon
include classpath("application.conf")

play.http.secret.key = "replace-with-a-random-secret"

bitcoin-s {
  network = mainnet

  bitcoind {
    uri = "http://127.0.0.1:8333"
    rpcUri = "http://127.0.0.1:8332"
    auth.user = "opreturnbot"
    auth.password = "replace-me"
    receivingWalletName = "opreturn-receive"
    sendingWalletName = "opreturn-send"
  }

  lnd {
    rpcUri = "https://127.0.0.1:10009"
    macaroonFile = "/run/secrets/lnd-admin.macaroon"
    tlsCert = "/run/secrets/lnd-tls.cert"
  }
}
```

You can store a non-secret configuration in Nix. Use `configText` instead of
`configFile`:

```nix
services.op-return-bot = {
  enable = true;
  configText = ''
    include classpath("application.conf")

    bitcoin-s {
      network = mainnet
      bitcoind.uri = "http://127.0.0.1:8333"
      bitcoind.rpcUri = "http://127.0.0.1:8332"
    }
  '';
};
```

Nix stores `configText` in the Nix store. Local users can read this content.
Use `configFile` for passwords, tokens, macaroons, and private keys.

If a reverse proxy serves the site, keep the default loopback address. If
remote clients must connect to the port, set `openFirewall = true`.

Deploy the NixOS system:

```shell
sudo nixos-rebuild switch --flake .#server
systemctl status op-return-bot
```

Build or run the package directly with these commands:

```shell
nix build path:.
nix run path:. -- -Dconfig.file=/absolute/path/to/op-return-bot.conf
```
