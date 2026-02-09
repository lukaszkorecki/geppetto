class Geppetto < Formula
  desc "Lightweight process orchestrator for development environments"
  homepage "https://github.com/lukaszkorecki/geppetto"
  version "0.0.3"

  on_macos do
    on_arm do
      url "https://github.com/lukaszkorecki/geppetto/releases/download/v#{version}/geppetto-macos-arm64.tar.gz"
      sha256 "a79580c465cc4ee3a0f9ea730ae0bac4107ddd3062464f16906a26e73184634b"
    end
  end

  def install
    bin.install "geppetto"
  end

  test do
    system "#{bin}/geppetto", "--help"
  end
end
