/** Wire types mirroring the account-upgrade-backend REST API. */

export type RequestSource = 'BATCH' | 'REALTIME';
export type ProcessingStatus = 'ELIGIBLE' | 'INELIGIBLE';
export type ReceiptStatus = 'ACCEPTED' | 'REJECTED';
export type RecipientRole = 'USER' | 'PARENT';

/** Request body for both ingestion endpoints. Business rules are checked by the backend. */
export interface UpgradeRequest {
  userId: string;
  userName: string | null;
  age: number | null;
  balance: number | null;
  parentEmail: string | null;
}

export interface IngestionReceipt {
  userId: string;
  /** Absent when the request was rejected. */
  eventId?: string;
  source: RequestSource;
  status: ReceiptStatus;
  /** Present only when the request was rejected. */
  error?: string;
}

export interface BatchIngestionResponse {
  total: number;
  accepted: number;
  rejected: number;
  receipts: IngestionReceipt[];
}

export interface ProcessedUpgrade {
  eventId: string;
  userId: string;
  source: RequestSource;
  status: ProcessingStatus;
  reasons: string[];
  notificationSent: boolean;
  processedAt: string;
}

export interface ProcessedUpgradeFilter {
  status?: ProcessingStatus | null;
  userId?: string | null;
}

export interface EmailMessage {
  eventId: string;
  role: RecipientRole;
  recipient: string;
  subject: string;
  body: string;
  createdAt: string;
}

/** RFC 9457 problem details as returned by the backend's GlobalExceptionHandler. */
export interface ProblemDetail {
  status?: number;
  title?: string;
  detail?: string;
  errors?: string[];
}
