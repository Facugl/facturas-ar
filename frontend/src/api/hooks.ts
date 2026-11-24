import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query'

import * as api from './client'
import {
  isInFlight,
  type Corrections,
  type History,
  type Identity,
  type Job,
  type Upload,
} from './types'

/**
 * Query keys in one place, because a typo in one is a cache that never
 * invalidates and a screen that never updates.
 */
export const keys = {
  me: ['me'] as const,
  job: (jobId: string) => ['job', jobId] as const,
  history: (filter: HistoryFilter) => ['history', filter] as const,
}

export type HistoryFilter = {
  from?: string
  to?: string
  page: number
}

/**
 * Who is signed in, or null.
 *
 * `retry: false` matters: a 401 here is the answer, not a failure, and
 * React Query's default of retrying three times would make "you are not
 * logged in" take a second and a half to say.
 */
export function useIdentity(): UseQueryResult<Identity | null> {
  return useQuery({
    queryKey: keys.me,
    queryFn: async () => {
      try {
        return await api.get<Identity>('/auth/me')
      } catch (error) {
        if (error instanceof api.Unauthorized) {
          return null
        }
        throw error
      }
    },
    retry: false,
    staleTime: Infinity,
  })
}

export function useRegister() {
  return useMutation({
    mutationFn: (credentials: { email: string; password: string }) =>
      api.post<void>('/auth/register', credentials),
  })
}

export function useSignIn() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (credentials: { email: string; password: string }) =>
      api.post<Identity>('/auth/login', credentials),
    onSuccess: (identity) => queryClient.setQueryData(keys.me, identity),
  })
}

export function useSignOut() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/auth/logout'),
    // Everything cached belonged to the user who just left. Clearing it
    // is not tidiness: the next person to sign in on this browser would
    // otherwise be shown somebody else's jobs from the cache, before any
    // request went out to be refused.
    onSuccess: () => queryClient.clear(),
  })
}

export function useUpload() {
  return useMutation({
    mutationFn: (file: File) => api.upload<Upload>('/documents', file),
  })
}

/**
 * One job, polled while it is still moving.
 *
 * The interval is 1.5s and stops the moment the status leaves
 * `PENDING`/`PROCESSING`, which is what makes this cheap: the API caches
 * this read in Redis for thirty seconds precisely because the frontend
 * hammers it, so most of these polls never reach Postgres.
 */
export function useJob(jobId: string | null): UseQueryResult<Job | null> {
  return useQuery({
    queryKey: keys.job(jobId ?? ''),
    queryFn: () => api.get<Job>(`/jobs/${jobId}`, true),
    enabled: jobId !== null,
    refetchInterval: (query) => {
      const job = query.state.data
      return job && isInFlight(job.status) ? 1500 : false
    },
  })
}

/**
 * Confirms a job, with corrections.
 *
 * Sends all nine fields, every time, and that is deliberate rather than
 * lazy: the server decides which of them differ from what is stored
 * (ADR 016). The form does not have to track what the user touched, and
 * cannot get it wrong.
 */
export function useConfirm(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (fields: Corrections) =>
      api.put<Job>(`/jobs/${jobId}/confirm`, { fields }),
    onSuccess: (job) => {
      if (job) {
        queryClient.setQueryData(keys.job(jobId), job)
      }
    },
  })
}

export function useExport() {
  return useMutation({
    mutationFn: ({ from, to }: { from: string; to: string }) =>
      api.download(`/export/csv?from=${from}&to=${to}`),
  })
}

/**
 * The history list.
 *
 * The filter is part of the query key, so changing the date range is a
 * different cached query rather than a refetch that briefly shows the
 * wrong rows under the new dates.
 *
 * `placeholderData` keeps the previous page on screen while the next one
 * loads, which is what stops the list from collapsing to empty and back
 * on every page change.
 */
export function useHistory(filter: HistoryFilter): UseQueryResult<History | null> {
  return useQuery({
    queryKey: keys.history(filter),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(filter.page), size: '20' })
      if (filter.from) {
        params.set('from', filter.from)
      }
      if (filter.to) {
        params.set('to', filter.to)
      }
      return api.get<History>(`/jobs?${params}`)
    },
    placeholderData: (previous) => previous,
  })
}
