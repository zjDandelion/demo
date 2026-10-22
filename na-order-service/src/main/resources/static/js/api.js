(function () {
    'use strict';

    async function request(method, url, body) {
        var options = {
            method: method,
            headers: { 'Accept': 'application/json' }
        };
        if (body !== undefined) {
            options.headers['Content-Type'] = 'application/json';
            options.body = JSON.stringify(body);
        }

        var response;
        try {
            response = await fetch(url, options);
        } catch (e) {
            throw new Error('网络连接失败，请稍后重试');
        }

        if (response.status === 401) {
            window.location.href = '/login';
            throw new Error('登录已失效，请重新登录');
        }

        var data = null;
        var contentType = response.headers.get('content-type') || '';
        if (contentType.indexOf('application/json') >= 0) {
            try {
                data = await response.json();
            } catch (e) {
                data = null;
            }
        }

        if (!response.ok) {
            var error = new Error(data && data.message ? data.message : '请求失败（' + response.status + '）');
            error.status = response.status;
            error.fieldErrors = data && data.fieldErrors ? data.fieldErrors : null;
            throw error;
        }
        return data;
    }

    function get(url) {
        return request('GET', url);
    }

    function post(url, body) {
        return request('POST', url, body === undefined ? {} : body);
    }

    function toastStack() {
        var stack = document.getElementById('toast-stack');
        if (!stack) {
            stack = document.createElement('div');
            stack.id = 'toast-stack';
            stack.className = 'toast-stack';
            document.body.appendChild(stack);
        }
        return stack;
    }

    function showToast(message, type) {
        var toast = document.createElement('div');
        toast.className = 'toast' + (type ? ' toast-' + type : '');
        toast.setAttribute('role', type === 'error' ? 'alert' : 'status');
        toast.textContent = message;
        toastStack().appendChild(toast);
        window.setTimeout(function () {
            toast.remove();
        }, 3200);
    }

    function formatMoney(value) {
        var number = Number(value);
        if (!isFinite(number)) {
            number = 0;
        }
        return number.toFixed(2);
    }

    var STATUS_LABELS = {
        PENDING_PAYMENT: '待支付',
        PAID: '已支付',
        CANCELLED: '已取消'
    };

    var STATUS_CLASSES = {
        PENDING_PAYMENT: 'status-pending',
        PAID: 'status-paid',
        CANCELLED: 'status-cancelled'
    };

    function statusLabel(status) {
        return STATUS_LABELS[status] || status;
    }

    function statusClass(status) {
        return STATUS_CLASSES[status] || 'status-cancelled';
    }

    window.api = {
        get: get,
        post: post,
        request: request
    };
    window.UI = {
        showToast: showToast,
        formatMoney: formatMoney,
        statusLabel: statusLabel,
        statusClass: statusClass
    };
})();
