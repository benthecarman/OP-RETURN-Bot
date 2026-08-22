{
  description = "OP_RETURN Bot package and NixOS module";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

    sbt-derivation = {
      url = "github:zaninime/sbt-derivation";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    scalastr-src = {
      url = "github:benthecarman/scalastr/b957faa6407f511cd05c22ced225c5f15455d62a";
      flake = false;
    };

    bitcoin-s-src = {
      url = "github:bitcoin-s/bitcoin-s/195cfbd27336328eac5c80dfb02cf7e8c09cbb90";
      flake = false;
    };
  };

  outputs =
    inputs@{
      self,
      nixpkgs,
      sbt-derivation,
      ...
    }:
    let
      supportedSystems = [
        "aarch64-linux"
        "x86_64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
      pkgsFor = system: import nixpkgs { inherit system; };
    in
    {
      packages = forAllSystems (
        system:
        let
          pkgs = pkgsFor system;
          buildPkgs = pkgs.extend (
            _final: _prev: {
              sbt = pkgs.sbt.override { jre = pkgs.jdk11_headless; };
            }
          );
        in
        {
          default = self.packages.${system}.op-return-bot;
          op-return-bot = sbt-derivation.lib.mkSbtDerivation {
            pkgs = buildPkgs;
            pname = "op-return-bot";
            version = "0.1.0";

            src = pkgs.lib.cleanSourceWith {
              src = ./.;
              filter =
                path: type:
                let
                  name = baseNameOf path;
                in
                !(
                  name == ".git"
                  || name == ".direnv"
                  || name == ".github"
                  || name == "flake.lock"
                  || name == "flake.nix"
                  || name == "nix"
                  || name == "README.md"
                  || name == "result"
                  || name == "target"
                );
            };

            depsSha256 = "sha256-ngY5zNHPVauwUfJnQFcmR9/NfIqPmknydJwLRHvTevg=";
            depsWarmupCommand = ''
              (
                cd .nix-bitcoin-s
                sbt -Dsbt.supershell=false \
                  'set ThisBuild / version := "1.9.7-412-195cfbd2-SNAPSHOT"' \
                  'secp256k1jni/publishLocal' \
                  'cryptoJVM/publishLocal' \
                  'coreJVM/publishLocal' \
                  'asyncUtilsJVM/publishLocal' \
                  'appCommons/publishLocal' \
                  'tor/publishLocal' \
                  'keyManager/publishLocal' \
                  'dbCommons/publishLocal' \
                  'esplora/publishLocal' \
                  'feeProvider/publishLocal' \
                  'bitcoindRpc/publishLocal' \
                  'lndRpc/publishLocal' \
                  'lnurl/publishLocal'
              )
              (
                cd .nix-scalastr
                sbt -Dsbt.supershell=false \
                  'set ThisBuild / version := "0.0.0-77-b957faa6-SNAPSHOT"' \
                  'core/publishLocal' \
                  'client/publishLocal'
              )
              sbt -Dsbt.supershell=false compile
            '';
            CI = "true";

            nativeBuildInputs = [
              pkgs.git
              pkgs.makeWrapper
            ];

            overrideDepsAttrs = _final: previous: {
              postUnpack = (previous.postUnpack or "") + ''
                cp -R "${inputs.bitcoin-s-src}" "$sourceRoot/.nix-bitcoin-s"
                cp -R "${inputs.scalastr-src}" "$sourceRoot/.nix-scalastr"
                chmod -R u+w "$sourceRoot/.nix-bitcoin-s" "$sourceRoot/.nix-scalastr"

                (
                  cd "$sourceRoot/.nix-bitcoin-s"
                  git init --quiet
                  git -c user.name=Nix -c user.email=nix@localhost \
                    commit --quiet --allow-empty --message=Source
                  git tag 1.9.7
                )
                (
                  cd "$sourceRoot/.nix-scalastr"
                  git init --quiet
                  git -c user.name=Nix -c user.email=nix@localhost \
                    commit --quiet --allow-empty --message=Source
                  git tag 0.0.0
                )
              '';
            };

            buildPhase = ''
              runHook preBuild
              sbt -Dsbt.supershell=false stage
              runHook postBuild
            '';

            installPhase = ''
              runHook preInstall

              mkdir -p "$out/bin" "$out/libexec/op-return-bot"
              cp -R target/universal/stage/. "$out/libexec/op-return-bot/"

              makeWrapper \
                "$out/libexec/op-return-bot/bin/op-return-bot" \
                "$out/bin/op-return-bot" \
                --set JAVA_HOME "${pkgs.jdk11_headless}" \
                --prefix PATH : "${pkgs.lib.makeBinPath [ pkgs.jdk11_headless ]}"

              runHook postInstall
            '';

            meta = {
              description = "Web service that publishes messages in Bitcoin OP_RETURN outputs";
              homepage = "https://opreturnbot.com";
              license = pkgs.lib.licenses.mit;
              mainProgram = "op-return-bot";
              platforms = supportedSystems;
            };
          };
        }
      );

      nixosModules = {
        default = self.nixosModules.op-return-bot;
        op-return-bot = import ./nix/module.nix { inherit self; };
      };

      overlays.default = final: _prev: {
        op-return-bot = self.packages.${final.stdenv.hostPlatform.system}.op-return-bot;
      };
    };
}
