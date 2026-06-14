/** Display label for a route in selects and lists (code + name, no route number). */
export function formatRouteLabel(route: { code?: string | null; name: string }): string {
  const code = route.code?.trim();
  return code ? `${code} — ${route.name}` : route.name;
}
