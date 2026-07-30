import type { Activity } from '../api'

interface ActivityFeedProps {
  activities: Activity[]
}

export function ActivityFeed({ activities }: ActivityFeedProps) {
  return (
    <section className="activity-feed">
      <h2>Agent Activity</h2>
      {activities.length === 0 ? (
        <p className="empty">No activity yet — start an optimization.</p>
      ) : (
        <ul>
          {activities.map((activity, index) => (
            <li key={`${activity.timestamp}-${index}`}>
              <span className="agent">{activity.agent}</span>
              <span className="message">{activity.message}</span>
              <time>{new Date(activity.timestamp).toLocaleTimeString()}</time>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
