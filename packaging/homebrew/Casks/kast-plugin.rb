# frozen_string_literal: true

artifact_version = "0.7.29"
artifact_root = ENV.fetch("HOMEBREW_KAST_ARTIFACT_ROOT", "https://github.com/amichne").chomp("/")
plugin_release_root = ENV.fetch(
  "HOMEBREW_KAST_PLUGIN_RELEASE_ROOT",
  "#{artifact_root}/kast/releases/download",
).chomp("/")

jetbrains_config_root = lambda do
  Pathname.new(
    ENV.fetch(
      "KAST_JETBRAINS_CONFIG_ROOT",
      "#{Dir.home}/Library/Application Support/JetBrains",
    ),
  )
end

jetbrains_plugin_dirs = lambda do
  root = jetbrains_config_root.call
  next [] unless root.directory?

  dirs = root.children.filter_map do |path|
    next unless path.directory?

    product = path.basename.to_s
    match = product.match(/\A([A-Za-z][A-Za-z0-9]*)(\d{4})\.(\d+)(?:\.(\d+))?\z/)
    next unless match

    [
      match[1],
      match[2].to_i,
      match[3].to_i,
      (match[4] || "0").to_i,
      path/"plugins",
    ]
  end

  dirs.sort_by { |product, year, minor, patch, path| [product, -year, -minor, -patch, path.to_s] }.map(&:last)
end

cask "kast-plugin" do
  version artifact_version
  sha256 "02e49c5d5f08f3a52d50f6ca925ed7b044cf5a81861fa1c4a421fbe61283e46f"

  url "#{plugin_release_root}/v#{version}/kast-intellij-v#{version}.zip"
  name "Kast IntelliJ Plugin"
  desc "IntelliJ IDEA plugin bundle for Kast Kotlin analysis"
  homepage "https://github.com/amichne/kast"

  livecheck do
    url "https://github.com/amichne/kast/releases"
    strategy :github_releases
  end

  stage_only true

  postflight do
    plugin_root = staged_path/"backend-intellij"
    plugins_dirs = jetbrains_plugin_dirs.call

    if plugins_dirs.empty?
      opoo <<~EOS
        No JetBrains IDE config directory was found under #{jetbrains_config_root.call}.
        Launch a JetBrains IDE once, then run `brew reinstall kast-plugin`.
      EOS
      next
    end

    linked_dirs = []

    plugins_dirs.each do |plugins_dir|
      link_path = plugins_dir/"kast"
      FileUtils.mkdir_p plugins_dir

      if link_path.symlink?
        current = link_path.readlink.to_s
        if current == plugin_root.to_s
          linked_dirs << plugins_dir
          next
        end
        unless current.include?("/kast-plugin/")
          opoo "Not replacing existing link: #{link_path} -> #{current}"
          next
        end
        link_path.delete
      elsif link_path.exist?
        opoo "Not replacing existing path: #{link_path}"
        next
      end

      FileUtils.ln_s plugin_root, link_path
      linked_dirs << plugins_dir
    end

    if linked_dirs.empty?
      opoo "Kast plugin was not linked into any JetBrains IDE config directory"
    else
      linked_count = linked_dirs.length
      noun = (linked_count == 1) ? "directory" : "directories"
      ohai "Linked Kast plugin into #{linked_count} JetBrains IDE config #{noun}"
    end
  end

  uninstall_postflight do
    plugin_root = staged_path/"backend-intellij"

    jetbrains_plugin_dirs.call.each do |plugins_dir|
      link_path = plugins_dir/"kast"
      next unless link_path.symlink?

      current = link_path.readlink.to_s
      next if current != plugin_root.to_s && current.exclude?("/Caskroom/kast-plugin/")

      link_path.delete
    end
  end

  caveats <<~EOS
    kast-plugin links the Homebrew-managed plugin into every JetBrains IDE config
    directory found on this Mac. Restart each IDE to load it.

    Set KAST_JETBRAINS_CONFIG_ROOT before install if your JetBrains config
    directory is not under ~/Library/Application Support/JetBrains.
  EOS
end
