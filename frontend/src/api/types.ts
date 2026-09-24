/**
 * TypeScript mirrors of the backend DTOs (Phase 8).
 *
 * Sources of truth (do not edit here — regenerate from the backend):
 * - com.fieldwork.ops.workorder.dto.* (WorkOrderResponse, CommentResponse,
 *   AttachmentResponse, StatusHistoryResponse, WorkOrderListResponse)
 * - com.fieldwork.ops.reporting.dto.DashboardSummaryResponse
 * - com.fieldwork.ops.common.web.ErrorResponse
 * - com.fieldwork.ops.common.security.dto.* (TokenResponse)
 * - com.fieldwork.ops.workorder.WorkOrderStatus / WorkOrderPriority
 * - com.fieldwork.ops.auth.RoleName
 */

export type WorkOrderStatus =
  | 'OPEN'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'ON_HOLD'
  | 'RESOLVED'
  | 'CLOSED'
  | 'CANCELLED';

export type WorkOrderPriority = 'P1' | 'P2' | 'P3' | 'P4';

export type RoleName = 'ADMIN' | 'DISPATCHER' | 'TECHNICIAN' | 'REQUESTER';

export interface UserSummary {
  id: string;
  username: string;
  fullName: string;
}

export interface TeamSummary {
  id: string;
  name: string;
}

export interface SlaInfo {
  responseDueAt: string | null;
  resolutionDueAt: string | null;
  dueAt: string | null;
  onHold: boolean;
  pausedSeconds: number;
}

export interface AttachmentUploader {
  id: string;
  username: string;
  fullName: string;
}

export interface AttachmentResponse {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedBy: AttachmentUploader | null;
  uploadedAt: string;
}

export interface WorkOrderResponse {
  id: string;
  ticketNumber: string;
  title: string;
  description: string | null;
  status: WorkOrderStatus;
  priority: WorkOrderPriority;
  category: string | null;
  requester: UserSummary | null;
  assignee: UserSummary | null;
  team: TeamSummary | null;
  sla: SlaInfo | null;
  estimatedHours: number | null;
  respondedAt: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string | null;
  updatedBy: string | null;
  attachments: AttachmentResponse[];
}

/** Page wrapper for GET /api/v1/work-orders. Mirrors WorkOrderListResponse. */
export interface WorkOrderListResponse {
  content: WorkOrderResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface StatusHistoryResponse {
  id: string;
  fromStatus: WorkOrderStatus | null;
  toStatus: WorkOrderStatus;
  changedBy: UserSummary | null;
  changedAt: string;
  note: string | null;
}

export interface CommentResponse {
  id: string;
  body: string;
  internal: boolean;
  author: UserSummary | null;
  createdAt: string;
}

export interface DashboardSummaryResponse {
  totalWorkOrders: number;
  /** Status name -> count, e.g. { OPEN: 12, IN_PROGRESS: 5 }. */
  workOrdersByStatus: Record<string, number>;
  /** Priority name -> count of OPEN tickets, e.g. { P1: 2, P3: 9 }. */
  openWorkOrdersByPriority: Record<string, number>;
  openWorkOrders: number;
  unassignedWorkOrders: number;
  openBreaches: number;
  /** Share of active tickets with no unresolved breach, 0-100. */
  slaCompliancePercent: number;
  technicianLoad: TechnicianLoadResponse[];
  activePolicies: number;
  generatedAt: string;
}

export interface TechnicianLoadResponse {
  id: string;
  username: string;
  fullName: string;
  openTickets: number;
}

export interface TechnicianResponse {
  id: string;
  username: string;
  fullName: string;
  teamName: string | null;
  openTickets: number;
}

export type BreachType =
  | 'RESPONSE'
  | 'RESOLUTION';

export interface BreachResponse {
  id: string;
  workOrderId: string;
  ticketNumber: string;
  policyId: string | null;
  policyName: string | null;
  breachType: BreachType;
  breachedAt: string;
  detectedAt: string;
  resolvedAt: string | null;
  note: string | null;
}

/** Standard backend error envelope (com.fieldwork.ops.common.web.ErrorResponse). */
export interface ErrorEnvelope {
  timestamp?: string;
  status: number;
  error?: string;
  message: string;
  path?: string;
  fieldErrors?: Array<{ field: string; message: string }>;
}

/** Token pair from POST /api/v1/auth/login and /api/v1/auth/refresh. */
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
}

/** Decoded access-token claims (client-side decode only — never verified here). */
export interface JwtClaims {
  sub: string;
  email: string;
  role: RoleName;
  exp?: number;
  iat?: number;
}
