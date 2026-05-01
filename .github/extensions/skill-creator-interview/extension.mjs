/**
 * skill-creator-interview extension
 *
 * Two-part interview gate for the skill-creator workflow:
 *
 *  1. skill_creator_interview tool — the agent calls this (after gathering answers
 *     from the user via ask_user) to record the interview. Writing the token to the
 *     session workspace unlocks SKILL.md editing for this session.
 *
 *  2. onPreToolUse hook — intercepts any edit/create call targeting a SKILL.md file.
 *     If no interview token exists for this session, it surfaces a confirmation prompt
 *     so the user sees the gate and the agent knows to run the interview first.
 */

import { joinSession } from "@github/copilot-sdk/extension";
import { existsSync, mkdirSync, writeFileSync } from "fs";
import { join } from "path";

const INTERVIEW_FILE = "skill-creator-interview.json";

function isSkillDefinitionFile(path) {
    return (
        typeof path === "string" &&
        path.includes(".github/skills/") &&
        path.endsWith("SKILL.md")
    );
}

function interviewFilePath(workspacePath) {
    return join(workspacePath, "files", INTERVIEW_FILE);
}

function interviewCompleted(workspacePath) {
    if (!workspacePath) return false;
    return existsSync(interviewFilePath(workspacePath));
}

const session = await joinSession({
    tools: [
        {
            name: "skill_creator_interview",
            description:
                "Records the skill creation or improvement interview and unlocks SKILL.md editing for this session. " +
                "MUST be called before editing any SKILL.md file. " +
                "Use ask_user to gather the answers from the user first, then call this tool with them.",
            parameters: {
                type: "object",
                properties: {
                    skill_name: {
                        type: "string",
                        description: "The name of the skill being created or improved.",
                    },
                    mode: {
                        type: "string",
                        enum: ["create", "improve"],
                        description: "Whether this is a new skill or an improvement to an existing one.",
                    },
                    // improve-mode fields
                    what_is_broken: {
                        type: "string",
                        description:
                            "(improve) What the user observed that felt wrong or suboptimal. Include any examples they provided.",
                    },
                    incorrect_patterns: {
                        type: "string",
                        description:
                            "(improve) Specific patterns the skill is generating incorrectly, if any.",
                    },
                    ideal_output_definition: {
                        type: "string",
                        description:
                            "What 'best practices' or 'ideal output' means to the user for this skill.",
                    },
                    run_evals: {
                        type: "boolean",
                        description:
                            "Whether to run evals before and after to measure the improvement.",
                    },
                    // create-mode fields
                    skill_purpose: {
                        type: "string",
                        description: "(create) What the skill should enable the agent to do.",
                    },
                    trigger_phrases: {
                        type: "string",
                        description:
                            "(create) When the skill should trigger and which user phrases invoke it.",
                    },
                    expected_output_format: {
                        type: "string",
                        description: "(create) The expected output format.",
                    },
                },
                required: ["skill_name", "mode"],
            },
            skipPermission: true,
            handler: async (args) => {
                const workspacePath = session.workspacePath;

                if (!workspacePath) {
                    return {
                        textResultForLlm:
                            `Interview for "${args.skill_name}" (${args.mode}) recorded in-memory — ` +
                            "session workspace unavailable. You may now proceed with skill file edits.",
                        resultType: "success",
                    };
                }

                const filesDir = join(workspacePath, "files");
                if (!existsSync(filesDir)) {
                    mkdirSync(filesDir, { recursive: true });
                }

                const record = {
                    skill_name: args.skill_name,
                    mode: args.mode,
                    completed_at: new Date().toISOString(),
                    answers: { ...args },
                };

                writeFileSync(interviewFilePath(workspacePath), JSON.stringify(record, null, 2));

                await session.log(
                    `Skill interview recorded: "${args.skill_name}" (${args.mode}) — SKILL.md editing unlocked.`,
                );

                return {
                    textResultForLlm:
                        `Interview complete for "${args.skill_name}" (${args.mode} mode). ` +
                        "Gate unlocked — you may now read and edit skill files.\n\n" +
                        `Recorded answers:\n${JSON.stringify(record.answers, null, 2)}`,
                    resultType: "success",
                };
            },
        },
    ],

    hooks: {
        onPreToolUse: async (input) => {
            const { toolName, toolArgs } = input;

            // Only intercept file write operations.
            if (toolName !== "edit" && toolName !== "create") return;

            // Only gate on SKILL.md files inside .github/skills/.
            const path = toolArgs?.path ?? "";
            if (!isSkillDefinitionFile(path)) return;

            // If the interview was completed this session, allow.
            if (interviewCompleted(session.workspacePath)) return;

            // No interview on record — surface a confirmation prompt.
            // Using "ask" rather than "deny" so the user retains final say.
            return {
                permissionDecision: "ask",
                permissionDecisionReason: [
                    "⛔ Interview required before editing a skill file.",
                    "",
                    `Target: ${path}`,
                    "",
                    "Before changing any SKILL.md, gather the user's intent with ask_user:",
                    "  1. What output did you see that felt wrong? (share an example if possible)",
                    "  2. Are there specific patterns being generated incorrectly?",
                    "  3. What does 'ideal output' mean to you here?",
                    "  4. Should we run evals before/after to verify the improvement?",
                    "",
                    "Then call skill_creator_interview with their answers to unlock editing.",
                    "",
                    "Already gathered intent from the conversation? Call skill_creator_interview",
                    "now with what you know before proceeding.",
                ].join("\n"),
            };
        },
    },
});
