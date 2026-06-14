/** Backend Jackson serializes Kotlin `isActive` as JSON `active`. */
export function readIsActive(item: { isActive?: boolean; active?: boolean }): boolean {
  return item.isActive ?? item.active ?? false;
}

export function withIsActive<T extends { isActive?: boolean; active?: boolean }>(
  item: T,
): T & { isActive: boolean } {
  return { ...item, isActive: readIsActive(item) };
}
