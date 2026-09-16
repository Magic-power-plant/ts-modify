package com.pingsu.tsmodify.typescript;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * KubeJS module bodies must reach Rhino without any surviving static module syntax.
 *
 * <p>The runtime module loader replaces {@code import}/{@code export} with its own
 * {@code require}/{@code exports} calls. Anything that survives the rewrite is a hard failure at
 * module execution time, where the message is only "identifier is a reserved word: export".</p>
 */
class TypeScriptModuleBodyExportTest {
    private static final List<String> EXPORT_SHAPES = List.of(
            "export enum Mode { A = \"a\", B = \"b\" }\n",
            "export interface Shape { readonly value: number }\n",
            "export class Entry implements Shape {\n"
                    + "  static readonly created: string[] = []\n"
                    + "  constructor(readonly value: number) { Entry.created.push(String(value)) }\n"
                    + "  get doubled(): number { return this.value * 2 }\n"
                    + "}\n",
            "export function fn(input: number, factor: number = 3): number { return input * factor }\n",
            "export const LABELS = [\"a\", \"b\"] as const\n",
            "export const value: number = 1\n",
            "export const first = 1\n"
                    + "export const second = 2\n",
            "export default function label(value: string): string { return value + \":ok\" }\n",
            "export { value } from \"./other\"\n",
            // No trailing newline: a file that simply ends after the last statement.
            "export const last = 1",
            "export class Last { }",
            "export enum LastMode { A }",
            "export function lastFn(): number { return 1 }",
            // A statement after the export must not hide a missed rewrite.
            "export const tracked = 1\nconsole.info(tracked)\n");

    @Test
    void rewritesEveryExportShapeForTheKubeJsModuleLoader() {
        for (String source : EXPORT_SHAPES) {
            String body = TypeScriptTranspiler.transpileKubeJsModuleBody("probe.ts", source).code();

            assertFalse(containsResidualModuleSyntax(body),
                    () -> "residual module syntax for source:\n" + source + "\ntranspiled body:\n" + body);
        }
    }

    @Test
    void rewritesTheFullProbeHelperModule() {
        String source = """
                export enum ProbeMode {
                  Plain = "plain",
                  Scaled = "scaled"
                }

                export interface ProbeShape {
                  readonly label: string
                  readonly value: number
                }

                export class ProbeEntry implements ProbeShape {
                  static readonly created: string[] = []

                  constructor(readonly label: string, readonly value: number) {
                    ProbeEntry.created.push(label)
                  }

                  get doubled(): number {
                    return this.value * 2
                  }
                }

                export function scaledValue(input: number, factor: number = 3): number {
                  const clamped = input < 0 ? 0 : input
                  return clamped * factor
                }

                export function describe(shape: ProbeShape, mode: ProbeMode = ProbeMode.Plain): string {
                  const suffix = mode === ProbeMode.Scaled ? `x${shape.value}` : `${shape.value}`
                  return `${shape.label}:${suffix}`
                }

                export const LABELS = ["alpha", "beta", "gamma", "delta"] as const
                """;

        String body = TypeScriptTranspiler.transpileKubeJsModuleBody("probe/helpers.ts", source).code();

        assertFalse(containsResidualModuleSyntax(body), () -> "transpiled body:\n" + body);
        assertTrue(body.contains("exports.LABELS"), () -> "transpiled body:\n" + body);
        assertTrue(body.contains("exports.scaledValue"), () -> "transpiled body:\n" + body);
    }

    @Test
    void keepsStaticClassFieldInitializers() {
        String source = """
                export class Entry {
                  static readonly created: string[] = []

                  constructor() {
                    Entry.created.push("x")
                  }
                }
                console.info(Entry.created.length)
                """;

        String body = TypeScriptTranspiler.transpileKubeJsModuleBody("probe.ts", source).code();

        assertFalse(containsResidualModuleSyntax(body), () -> "transpiled body:\n" + body);
        assertTrue(body.contains("Entry.created") && body.contains("[]"),
                () -> "the static field initializer was dropped; transpiled body:\n" + body);
    }

    private static boolean containsResidualModuleSyntax(String body) {
        return body.lines().anyMatch(line -> {
            String trimmed = line.stripLeading();
            return trimmed.startsWith("export ")
                    || trimmed.startsWith("export{")
                    || trimmed.startsWith("import ")
                    || trimmed.equals("export");
        });
    }
}
