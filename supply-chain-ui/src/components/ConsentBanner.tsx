interface ConsentBannerProps {
  code: string
  url: string
}

/** Shown while the backend waits on Person Server consent; the popup opens alongside. */
export function ConsentBanner({ code, url }: ConsentBannerProps) {
  return (
    <aside className="consent-banner">
      <strong>Authorization required.</strong> Approve this request in the consent window using code{' '}
      <code>{code}</code>. If the popup was blocked,{' '}
      <a href={url} target="_blank" rel="noreferrer">
        open the consent page
      </a>
      .
    </aside>
  )
}
