{
  description = "OP_RETURN Bot package and NixOS module";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

    sbt-derivation = {
      url = "github:zaninime/sbt-derivation";
      inputs.nixpkgs.follows = "nixpkgs";
    };

  };

  outputs =
    {
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

            depsSha256 = "sha256-/ZtRb21cVdTnEY24tk/J9jsJ7kSXGy0DVdQhOj/5V+8=";
            depsWarmupCommand = ''
              sbt -Dsbt.supershell=false compile
            '';
            overrideDepsAttrs = _final: _prev: {
              __structuredAttrs = true;
              GIT_SSL_CAINFO = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
              unsafeDiscardReferences.out = true;
            };
            CI = "true";

            nativeBuildInputs = [
              pkgs.cacert
              pkgs.git
              pkgs.makeWrapper
            ];

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
