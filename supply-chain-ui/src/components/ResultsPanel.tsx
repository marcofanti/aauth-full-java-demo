import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

interface ResultsPanelProps {
  report: string | null
}

export function ResultsPanel({ report }: ResultsPanelProps) {
  return (
    <section className="results-panel">
      <h2>Optimization Results</h2>
      {report ? (
        <article className="markdown">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{report}</ReactMarkdown>
        </article>
      ) : (
        <p className="empty">Results appear here when an optimization completes.</p>
      )}
    </section>
  )
}
