package io.github.amichne.kast.cli

internal enum class CliCompletionShell {
    BASH,
    ZSH,
}

internal object CliCompletionScripts {
    fun render(shell: CliCompletionShell): String {
        return when (shell) {
            CliCompletionShell.BASH -> renderBashStyleScript(includeZshBootstrap = false)
            CliCompletionShell.ZSH -> renderBashStyleScript(includeZshBootstrap = true)
        }
    }

    private fun renderBashStyleScript(includeZshBootstrap: Boolean): String {
        val commands = CliCommandCatalog.visibleCommands()
        val topLevelTopics = listOf("help", "version") + CliCommandCatalog.topLevelCommandTopics()
        val prefixMap = buildPrefixMap(commands)
        val optionMap = commands.associate { command ->
            command.path to command.options.map { option -> "--${option.key}=" }
        }
        val completionOptions = commands
            .flatMap(CliCommandMetadata::options)
            .distinctBy(CliOptionMetadata::key)
            .filter { option -> option.completionKind != CliOptionCompletionKind.NONE }
        val dollar = '$'

        return buildString {
            appendLine("# shellcheck shell=bash")
            appendLine("# Opt in with:")
            appendLine("#   source <($CLI_EXECUTABLE_NAME completion ${if (includeZshBootstrap) "zsh" else "bash"})")
            if (includeZshBootstrap) {
                appendLine("autoload -U +X bashcompinit && bashcompinit")
                appendLine()
            }
            appendLine("__kast_join_words() {")
            appendLine("  local IFS=/")
            appendLine("  printf '%s' \"${dollar}*\"")
            appendLine("}")
            appendLine()
            appendLine("__kast_subcommands() {")
            appendLine("  local key")
            appendLine("  key=\"${dollar}(__kast_join_words \"${dollar}@\")\"")
            appendLine("  case \"${dollar}key\" in")
            appendPrintfCaseArm("", topLevelTopics)
            prefixMap.forEach { (prefix, values) ->
                appendPrintfCaseArm(prefix.joinToString("/"), values)
            }
            appendLine("    *)")
            appendLine("      return 0")
            appendLine("      ;;")
            appendLine("  esac")
            appendLine("}")
            appendLine()
            appendLine("__kast_options_for() {")
            appendLine("  local key")
            appendLine("  key=\"${dollar}(__kast_join_words \"${dollar}@\")\"")
            appendLine("  case \"${dollar}key\" in")
            optionMap.forEach { (path, options) ->
                if (options.isNotEmpty()) {
                    appendPrintfCaseArm(path.joinToString("/"), options)
                }
            }
            appendLine("    *)")
            appendLine("      return 0")
            appendLine("      ;;")
            appendLine("  esac")
            appendLine("}")
            appendLine()
            appendLine("__kast_complete_values() {")
            appendLine("  local values=\"${dollar}1\"")
            appendLine("  local current=\"${dollar}2\"")
            appendLine("  local prefix=\"${dollar}3\"")
            appendLine("  COMPREPLY=( ${dollar}(compgen -W \"${dollar}values\" -- \"${dollar}current\") )")
            appendLine("  local index")
            appendLine("  for index in \"${dollar}{!COMPREPLY[@]}\"; do")
            appendLine("    COMPREPLY[${dollar}index]=\"${dollar}{prefix}${dollar}{COMPREPLY[${dollar}index]}\"")
            appendLine("  done")
            appendLine("}")
            appendLine()
            appendLine("__kast_complete_path_list() {")
            appendLine("  local current_value=\"${dollar}1\"")
            appendLine("  local option_prefix=\"${dollar}2\"")
            appendLine("  local scope=\"${dollar}3\"")
            appendLine("  local leading=\"\"")
            appendLine("  local tail=\"${dollar}current_value\"")
            appendLine("  local matches")
            appendLine("  local match")
            appendLine("  COMPREPLY=()")
            appendLine("  if [[ \"${dollar}current_value\" == *,* ]]; then")
            appendLine("    leading=\"${dollar}{current_value%,*},\"")
            appendLine("    tail=\"${dollar}{current_value##*,}\"")
            appendLine("  fi")
            appendLine("  if [[ \"${dollar}scope\" == directories ]]; then")
            appendLine("    matches=\"${dollar}(compgen -d -- \"${dollar}tail\")\"")
            appendLine("  else")
            appendLine("    matches=\"${dollar}(compgen -f -- \"${dollar}tail\")\"")
            appendLine("  fi")
            appendLine("  while IFS= read -r match; do")
            appendLine("    [[ -n \"${dollar}match\" ]] || continue")
            appendLine("    COMPREPLY+=(\"${dollar}{option_prefix}${dollar}{leading}${dollar}{match}\")")
            appendLine("  done <<< \"${dollar}matches\"")
            appendLine("}")
            appendLine()
            appendLine("__kast_complete() {")
            appendLine("  local cur")
            appendLine("  cur=\"${dollar}{COMP_WORDS[COMP_CWORD]}\"")
            appendLine("  local -a positionals=()")
            appendLine("  local word")
            appendLine("  local index")
            appendLine("  local suggestions=\"\"")
            appendLine("  local subcommands")
            appendLine("  local options")
            appendLine("  COMPREPLY=()")
            appendLine()
            appendLine("  for ((index=1; index<COMP_CWORD; index++)); do")
            appendLine("    word=\"${dollar}{COMP_WORDS[index]}\"")
            appendLine("    [[ \"${dollar}word\" == --* ]] && continue")
            appendLine("    positionals+=(\"${dollar}word\")")
            appendLine("  done")
            appendLine()
            appendLine("  if [[ ${dollar}{#positionals[@]} -gt 0 && \"${dollar}{positionals[0]}\" == help ]]; then")
            appendLine("    positionals=(\"${dollar}{positionals[@]:1}\")")
            appendLine("  fi")
            appendLine()
            appendLine("  case \"${dollar}cur\" in")
            completionOptions.forEach { option ->
                when (option.completionKind) {
                    CliOptionCompletionKind.DIRECTORY -> {
                        appendLine("    --${option.key}=*)")
                        appendLine("      __kast_complete_path_list \"${dollar}{cur#--${option.key}=}\" \"--${option.key}=\" directories")
                        appendLine("      return 0")
                        appendLine("      ;;")
                    }
                    CliOptionCompletionKind.FILE -> {
                        appendLine("    --${option.key}=*)")
                        appendLine("      __kast_complete_path_list \"${dollar}{cur#--${option.key}=}\" \"--${option.key}=\" files")
                        appendLine("      return 0")
                        appendLine("      ;;")
                    }
                    CliOptionCompletionKind.FILE_LIST -> {
                        appendLine("    --${option.key}=*)")
                        appendLine("      __kast_complete_path_list \"${dollar}{cur#--${option.key}=}\" \"--${option.key}=\" files")
                        appendLine("      return 0")
                        appendLine("      ;;")
                    }
                    CliOptionCompletionKind.BOOLEAN -> {
                        appendLine("    --${option.key}=*)")
                        appendLine("      __kast_complete_values \"true false\" \"${dollar}{cur#--${option.key}=}\" \"--${option.key}=\"")
                        appendLine("      return 0")
                        appendLine("      ;;")
                    }
                    CliOptionCompletionKind.NONE -> Unit
                }
            }
            appendLine("  esac")
            appendLine()
            appendLine("  subcommands=\"${dollar}(__kast_subcommands \"${dollar}{positionals[@]}\")\"")
            appendLine("  options=\"${dollar}(__kast_options_for \"${dollar}{positionals[@]}\")\"")
            appendLine("  if [[ -n \"${dollar}subcommands\" ]]; then")
            appendLine("    suggestions=\"${dollar}subcommands\"")
            appendLine("  fi")
            appendLine("  if [[ -n \"${dollar}options\" ]]; then")
            appendLine("    [[ -z \"${dollar}suggestions\" ]] || suggestions+=${dollar}'\\n'")
            appendLine("    suggestions+=\"${dollar}options\"")
            appendLine("  fi")
            appendLine("  [[ -z \"${dollar}suggestions\" ]] || suggestions+=${dollar}'\\n'")
            appendLine("  suggestions+=\"--help\"")
            appendLine("  if [[ ${dollar}{#positionals[@]} -eq 0 ]]; then")
            appendLine("    suggestions+=${dollar}'\\n''--version'")
            appendLine("  fi")
            appendLine("  COMPREPLY=( ${dollar}(compgen -W \"${dollar}suggestions\" -- \"${dollar}cur\") )")
            appendLine("}")
            appendLine()
            appendLine("complete -o default -o nospace -F __kast_complete $CLI_EXECUTABLE_NAME")
        }.trimEnd()
    }

    private fun buildPrefixMap(commands: List<CliCommandMetadata>): Map<List<String>, List<String>> {
        val prefixes = buildSet {
            commands.forEach { command ->
                for (index in 1 until command.path.size) {
                    add(command.path.take(index))
                }
            }
        }
        return prefixes.associateWith { prefix ->
            CliCommandCatalog.commandsUnder(prefix)
                .mapNotNull { command -> command.path.getOrNull(prefix.size) }
                .distinct()
        }
    }

    private fun StringBuilder.appendPrintfCaseArm(
        key: String,
        values: List<String>,
    ) {
        appendLine("    ${shellQuote(key)})")
        appendLine("      printf '%s\\n' ${values.joinToString(" ") { value -> shellQuote(value) }}")
        appendLine("      ;;")
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }
}
