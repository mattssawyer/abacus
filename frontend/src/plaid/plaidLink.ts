type LinkHandler = ReturnType<Window['Plaid']['create']>

let handler: LinkHandler | undefined

/**
 * Opens Plaid Link with a link token. Resolves with the public token when the user finishes,
 * or null when they close Link without an error. Rejects when Link fails. Opening Link again
 * replaces any handler still open.
 */
export function openPlaidLink(token: string): Promise<string | null> {
  return new Promise((resolve, reject) => {
    if (!window.Plaid) {
      reject(new Error('Plaid Link unavailable'))
      return
    }
    handler?.destroy()
    handler = window.Plaid.create({
      token,
      onSuccess(publicToken) {
        resolve(publicToken)
      },
      onExit(error) {
        if (error) reject(new Error('Plaid Link failed'))
        else resolve(null)
      },
    })
    handler.open()
  })
}

/** Tears down Link, for pages that unmount while it's open. */
export function closePlaidLink(): void {
  handler?.destroy()
  handler = undefined
}
