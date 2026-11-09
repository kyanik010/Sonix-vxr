/**
 * SONIX DIAGNOSTICS — Client-side controller for diagnostics UI and event monitoring.
 * Fully compatible with Android TV (D-pad remote control) and mobile touch screens.
 */
(function() {
  'use strict';

  let currentFilter = 'ALL';
  let activeEventList = [];
  let selectedEvent = null;

  function isBridgeAvailable() {
    return window.SonixDiagnosticsBridge && typeof window.SonixDiagnosticsBridge.getDashboardStats === 'function';
  }

  function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  function escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  const SonixDiagnosticsUI = {
    init: function() {
      this.bindButtons();
      this.hookGlobalErrors();
    },

    bindButtons: function() {
      const openBtn = document.getElementById('settings-open-diagnostics');
      if (openBtn) {
        openBtn.addEventListener('click', () => {
          if (typeof window.openModal === 'function') {
            window.openModal('modal-diagnostics');
          } else {
            const modal = document.getElementById('modal-diagnostics');
            if (modal) {
              modal.classList.add('active');
              modal.setAttribute('aria-hidden', 'false');
            }
          }
          this.refreshAll();
        });
      }

      const closeBtn = document.getElementById('close-diagnostics');
      if (closeBtn) {
        closeBtn.addEventListener('click', () => {
          if (typeof window.closeAllModals === 'function') window.closeAllModals();
          else {
            const modal = document.getElementById('modal-diagnostics');
            if (modal) modal.classList.remove('active');
          }
        });
      }

      const closeFooterBtn = document.getElementById('close-diagnostics-footer');
      if (closeFooterBtn) {
        closeFooterBtn.addEventListener('click', () => {
          if (typeof window.closeAllModals === 'function') window.closeAllModals();
          else {
            const modal = document.getElementById('modal-diagnostics');
            if (modal) modal.classList.remove('active');
          }
        });
      }

      // Refresh
      const refreshBtn = document.getElementById('diag-btn-refresh');
      if (refreshBtn) {
        refreshBtn.addEventListener('click', () => {
          this.refreshAll();
        });
      }

      // Clear Logs
      const clearBtn = document.getElementById('diag-btn-clear');
      if (clearBtn) {
        clearBtn.addEventListener('click', () => {
          if (confirm('هل أنت متأكد من مسح جميع سجلات التشخيص؟')) {
            if (isBridgeAvailable()) {
              window.SonixDiagnosticsBridge.clearLogs();
              this.refreshAll();
            }
          }
        });
      }

      // Toggle System Active
      const toggleActiveBtn = document.getElementById('diag-toggle-active');
      if (toggleActiveBtn) {
        toggleActiveBtn.addEventListener('click', () => {
          if (!isBridgeAvailable()) return;
          const stats = this.getStats();
          const newState = !stats.is_enabled;
          window.SonixDiagnosticsBridge.setDiagnosticsEnabled(newState);
          this.refreshDashboard();
        });
      }

      // Toggle Verbose
      const toggleVerboseBtn = document.getElementById('diag-toggle-verbose');
      if (toggleVerboseBtn) {
        toggleVerboseBtn.addEventListener('click', () => {
          if (!isBridgeAvailable()) return;
          const stats = this.getStats();
          const newState = !stats.is_verbose;
          window.SonixDiagnosticsBridge.setVerboseLogging(newState);
          this.refreshDashboard();
        });
      }

      // Export TXT
      const exportTxtBtn = document.getElementById('diag-btn-export-txt');
      if (exportTxtBtn) {
        exportTxtBtn.addEventListener('click', () => {
          this.handleExport('TXT');
        });
      }

      // Export JSON
      const exportJsonBtn = document.getElementById('diag-btn-export-json');
      if (exportJsonBtn) {
        exportJsonBtn.addEventListener('click', () => {
          this.handleExport('JSON');
        });
      }

      // Share Report
      const shareBtn = document.getElementById('diag-btn-share');
      if (shareBtn) {
        shareBtn.addEventListener('click', () => {
          const scope = document.getElementById('diag-scope-select')?.value || 'FULL';
          if (isBridgeAvailable()) {
            window.SonixDiagnosticsBridge.shareReport(scope, 'TXT');
          }
        });
      }

      // Copy Detail
      const copyDetailBtn = document.getElementById('diag-btn-copy-detail');
      if (copyDetailBtn) {
        copyDetailBtn.addEventListener('click', () => {
          if (!selectedEvent) return;
          const text = JSON.stringify(selectedEvent, null, 2);
          if (isBridgeAvailable()) {
            window.SonixDiagnosticsBridge.copyToClipboard(text, 'تفاصيل الخطأ');
          } else if (navigator.clipboard) {
            navigator.clipboard.writeText(text);
            alert('تم النسخ إلى الحافظة');
          }
        });
      }

      // Last error banner button
      const viewLastErrorBtn = document.getElementById('diag-view-last-error-btn');
      if (viewLastErrorBtn) {
        viewLastErrorBtn.addEventListener('click', () => {
          const stats = this.getStats();
          if (stats && stats.last_error) {
            this.showEventDetail(stats.last_error);
          }
        });
      }

      // Filters
      const filterButtons = document.querySelectorAll('.diag-filter-btn');
      filterButtons.forEach(btn => {
        btn.addEventListener('click', (e) => {
          filterButtons.forEach(b => b.classList.remove('active'));
          btn.classList.add('active');
          currentFilter = btn.dataset.filter || 'ALL';
          this.loadEvents();
        });
      });
    },

    hookGlobalErrors: function() {
      window.addEventListener('error', function(e) {
        try {
          if (isBridgeAvailable()) {
            window.SonixDiagnosticsBridge.logClientError(
              'WEBVIEW',
              'WINDOW_ERROR',
              e.message || 'JavaScript runtime error',
              (e.filename || '') + ':' + (e.lineno || 0) + ':' + (e.colno || 0) + '\n' + (e.error?.stack || '')
            );
          }
        } catch (_) {}
      });

      window.addEventListener('unhandledrejection', function(e) {
        try {
          if (isBridgeAvailable()) {
            const reason = e.reason;
            const msg = reason?.message || String(reason || 'Unhandled Promise Rejection');
            const stack = reason?.stack || '';
            window.SonixDiagnosticsBridge.logClientError(
              'WEBVIEW',
              'UNHANDLED_PROMISE',
              msg,
              stack
            );
          }
        } catch (_) {}
      });
    },

    getStats: function() {
      if (!isBridgeAvailable()) return { is_enabled: true, is_verbose: false, error_count: 0, warning_count: 0, success_count: 0, log_storage_size_bytes: 0 };
      try {
        const raw = window.SonixDiagnosticsBridge.getDashboardStats();
        return JSON.parse(raw);
      } catch (e) {
        return { is_enabled: true, is_verbose: false, error_count: 0, warning_count: 0, success_count: 0, log_storage_size_bytes: 0 };
      }
    },

    refreshAll: function() {
      this.refreshDashboard();
      this.loadEvents();
    },

    refreshDashboard: function() {
      const stats = this.getStats();

      const statusBadge = document.getElementById('diag-status-badge');
      const toggleActiveBtn = document.getElementById('diag-toggle-active');
      if (statusBadge && toggleActiveBtn) {
        if (stats.is_enabled) {
          statusBadge.textContent = 'نشط ومفعل';
          statusBadge.className = 'badge-status-on';
          toggleActiveBtn.textContent = 'إيقاف';
        } else {
          statusBadge.textContent = 'متوقف';
          statusBadge.className = 'badge-status-off';
          toggleActiveBtn.textContent = 'تفعيل';
        }
      }

      const toggleVerboseBtn = document.getElementById('diag-toggle-verbose');
      if (toggleVerboseBtn) {
        toggleVerboseBtn.textContent = stats.is_verbose ? 'مفعل ✅' : 'معطل';
        toggleVerboseBtn.style.color = stats.is_verbose ? '#4caf50' : '#888';
      }

      const errEl = document.getElementById('diag-error-count');
      if (errEl) errEl.textContent = stats.error_count || 0;

      const warnEl = document.getElementById('diag-warning-count');
      if (warnEl) warnEl.textContent = stats.warning_count || 0;

      const succEl = document.getElementById('diag-success-count');
      if (succEl) succEl.textContent = stats.success_count || 0;

      const sizeEl = document.getElementById('diag-storage-size');
      if (sizeEl) sizeEl.textContent = formatBytes(stats.log_storage_size_bytes || 0);

      // Last error banner
      const banner = document.getElementById('diag-last-error-banner');
      if (banner) {
        if (stats.last_error) {
          banner.style.display = 'block';
          const timeEl = document.getElementById('diag-last-error-time');
          const msgEl = document.getElementById('diag-last-error-msg');
          if (timeEl) timeEl.textContent = stats.last_error.iso_time || '';
          if (msgEl) msgEl.textContent = stats.last_error.message || '';
        } else {
          banner.style.display = 'none';
        }
      }
    },

    loadEvents: function() {
      if (!isBridgeAvailable()) return;
      let compFilter = null;
      let sevFilter = null;

      if (currentFilter === 'ERROR') sevFilter = 'ERROR';
      else if (currentFilter === 'WARN') sevFilter = 'WARN';
      else if (currentFilter === 'SUBTITLE') compFilter = 'SUBTITLE';
      else if (currentFilter === 'PLAYER') compFilter = 'PLAYER';
      else if (currentFilter === 'NETWORK') compFilter = 'NETWORK';
      else if (currentFilter === 'XTREAM') compFilter = 'XTREAM';
      else if (currentFilter === 'WEBVIEW') compFilter = 'WEBVIEW';

      try {
        const raw = window.SonixDiagnosticsBridge.getRecentEvents(150, compFilter, sevFilter, null);
        activeEventList = JSON.parse(raw) || [];
      } catch (e) {
        activeEventList = [];
      }

      const countLabel = document.getElementById('diag-events-count-label');
      if (countLabel) countLabel.textContent = `${activeEventList.length} أحداث`;

      const listContainer = document.getElementById('diag-events-list');
      if (!listContainer) return;

      listContainer.innerHTML = '';
      if (activeEventList.length === 0) {
        listContainer.innerHTML = '<div class="diag-empty-list">لا توجد سجلات تطابق الفلتر الحالي.</div>';
        return;
      }

      activeEventList.forEach((ev, idx) => {
        const item = document.createElement('div');
        item.className = 'diag-event-item focusable';
        item.tabIndex = 0;
        item.dataset.idx = idx;

        let badgeClass = 'badge-info';
        let badgeIcon = 'ℹ️';
        if (ev.severity === 'ERROR' || ev.severity === 'FATAL') {
          badgeClass = 'badge-danger';
          badgeIcon = '🔴';
        } else if (ev.severity === 'WARN') {
          badgeClass = 'badge-warning';
          badgeIcon = '⚠️';
        } else if (ev.severity === 'DEBUG') {
          badgeClass = 'badge-debug';
          badgeIcon = '🔍';
        }

        const timeStr = ev.iso_time ? ev.iso_time.split('T')[1]?.replace('Z', '') : '';

        item.innerHTML = `
          <div class="diag-event-row">
            <span class="diag-badge ${badgeClass}">${badgeIcon} ${ev.severity}</span>
            <span class="diag-component">${escapeHtml(ev.component)}</span>
            <span class="diag-time">${escapeHtml(timeStr)}</span>
          </div>
          <div class="diag-event-msg">${escapeHtml(ev.message)}</div>
          ${ev.stage ? `<div class="diag-event-stage">المرحلة: <strong>${escapeHtml(ev.stage)}</strong></div>` : ''}
        `;

        item.addEventListener('click', () => {
          document.querySelectorAll('.diag-event-item').forEach(el => el.classList.remove('selected'));
          item.classList.add('selected');
          this.showEventDetail(ev);
        });

        item.addEventListener('keydown', (e) => {
          if (e.key === 'Enter') {
            item.click();
          }
        });

        listContainer.appendChild(item);
      });

      // Auto-select first event if none selected
      if (!selectedEvent && activeEventList.length > 0) {
        this.showEventDetail(activeEventList[0]);
      }
    },

    showEventDetail: function(ev) {
      selectedEvent = ev;
      const detailContainer = document.getElementById('diag-detail-content');
      if (!detailContainer) return;

      let timelineEvents = [];
      if (ev.operation_id && isBridgeAvailable()) {
        try {
          const rawTl = window.SonixDiagnosticsBridge.getOperationTimeline(ev.operation_id);
          timelineEvents = JSON.parse(rawTl) || [];
        } catch (_) {}
      }

      let timelineHtml = '';
      if (timelineEvents.length > 1) {
        timelineHtml = `
          <div class="diag-timeline-section">
            <div class="diag-timeline-title">تسلسل مراحل العملية (${escapeHtml(ev.operation_name || ev.operation_id)}):</div>
            <div class="diag-timeline-flow">
              ${timelineEvents.map((te, tIdx) => {
                const isCurrent = te.id === ev.id;
                const statusColor = (te.severity === 'ERROR' || te.severity === 'FATAL') ? '#f44336' : (te.severity === 'WARN' ? '#ff9800' : '#4caf50');
                return `
                  <div class="diag-timeline-node ${isCurrent ? 'current' : ''}">
                    <span class="node-bullet" style="background:${statusColor}"></span>
                    <span class="node-stage">${escapeHtml(te.stage || te.severity)}</span>
                    <span class="node-msg">${escapeHtml(te.message.substring(0, 45))}...</span>
                  </div>
                `;
              }).join('')}
            </div>
          </div>
        `;
      }

      detailContainer.innerHTML = `
        <div class="diag-detail-view">
          <div class="detail-header-block">
            <div class="detail-title-line">
              <span class="diag-badge badge-${ev.severity.toLowerCase()}">${ev.severity}</span>
              <span class="detail-component-tag">${escapeHtml(ev.component)}</span>
              ${ev.category ? `<span class="detail-category-tag">${escapeHtml(ev.category)}</span>` : ''}
            </div>
            <div class="detail-full-msg">${escapeHtml(ev.message)}</div>
          </div>

          <div class="detail-grid-meta">
            <div class="meta-item"><label>الوقت:</label><span>${escapeHtml(ev.iso_time || '')}</span></div>
            ${ev.operation_name ? `<div class="meta-item"><label>العملية:</label><span>${escapeHtml(ev.operation_name)}</span></div>` : ''}
            ${ev.operation_id ? `<div class="meta-item"><label>معرّف العملية (OpID):</label><code>${escapeHtml(ev.operation_id)}</code></div>` : ''}
            ${ev.stage ? `<div class="meta-item"><label>المرحلة الفاشلة:</label><strong>${escapeHtml(ev.stage)}</strong></div>` : ''}
            ${ev.file_name ? `<div class="meta-item"><label>الملف والسطر:</label><code>${escapeHtml(ev.file_name)}:${ev.line_number || 0}</code></div>` : ''}
            ${ev.exception_class ? `<div class="meta-item"><label>نوع الاستثناء:</label><code>${escapeHtml(ev.exception_class)}</code></div>` : ''}
          </div>

          ${timelineHtml}

          ${ev.stack_trace ? `
            <div class="detail-stack-section">
              <div class="stack-title">مسار الاستدعاء الكامل (Stack Trace):</div>
              <pre class="diag-stack-pre"><code>${escapeHtml(ev.stack_trace)}</code></pre>
            </div>
          ` : ''}

          ${ev.metadata && Object.keys(ev.metadata).length > 0 ? `
            <div class="detail-metadata-section">
              <div class="metadata-title">بيانات تقنية إضافية (Metadata):</div>
              <pre class="diag-meta-pre"><code>${escapeHtml(JSON.stringify(ev.metadata, null, 2))}</code></pre>
            </div>
          ` : ''}
        </div>
      `;
    },

    handleExport: function(format) {
      const scope = document.getElementById('diag-scope-select')?.value || 'FULL';
      if (!isBridgeAvailable()) {
        alert('خدمة التشخيص غير متصلة بالجهاز');
        return;
      }
      try {
        const raw = window.SonixDiagnosticsBridge.exportReport(scope, format);
        const res = JSON.parse(raw);
        if (res.success && res.content) {
          // Copy to clipboard or prompt user
          window.SonixDiagnosticsBridge.copyToClipboard(res.content, `تقرير ${scope} (${format})`);
          alert(`تم إنشاء تقرير التشخيص (${format}) ونسخه إلى الحافظة بنجاح.`);
        } else {
          alert('تعذر إنشاء التقرير: ' + (res.error || 'خطأ غير معروف'));
        }
      } catch (err) {
        alert('خطأ أثناء تصدير التقرير: ' + err.message);
      }
    }
  };

  window.SonixDiagnosticsUI = SonixDiagnosticsUI;

  // Auto-init on DOMContentLoaded
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => SonixDiagnosticsUI.init());
  } else {
    SonixDiagnosticsUI.init();
  }
})();
