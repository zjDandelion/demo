(function () {
    'use strict';

    var page = document.getElementById('order-detail-page');
    var orderNo = page ? page.getAttribute('data-order-no') : '';
    var statusEl = document.getElementById('detail-status');
    var contentEl = document.getElementById('detail-content');
    var alertEl = document.getElementById('detail-alert');
    var itemsEl = document.getElementById('detail-items');
    var badgeEl = document.getElementById('detail-badge');
    var statusTextEl = document.getElementById('detail-status-text');
    var totalEl = document.getElementById('detail-total');
    var createdAtEl = document.getElementById('detail-created-at');
    var paidRowEl = document.getElementById('detail-paid-row');
    var paidAtEl = document.getElementById('detail-paid-at');
    var cancelledRowEl = document.getElementById('detail-cancelled-row');
    var cancelledAtEl = document.getElementById('detail-cancelled-at');
    var actionsEl = document.getElementById('detail-actions');

    var currentOrder = null;

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text !== undefined && text !== null) {
            node.textContent = text;
        }
        return node;
    }

    function showAlert(message) {
        alertEl.innerHTML = '';
        if (message) {
            alertEl.appendChild(el('div', 'alert alert-error', message));
        }
    }

    function formatDate(value) {
        if (!value) {
            return '-';
        }
        var date = new Date(value);
        if (isNaN(date.getTime())) {
            return String(value).replace('T', ' ');
        }
        return date.toLocaleString('zh-CN', { hour12: false });
    }

    function renderActions(order) {
        actionsEl.innerHTML = '';
        if (order.status === 'PENDING_PAYMENT') {
            var pay = el('button', 'btn btn-primary', '支付订单');
            pay.type = 'button';
            pay.addEventListener('click', function () {
                payOrder(order.orderNo, pay);
            });
            actionsEl.appendChild(pay);

            var cancel = el('button', 'btn btn-danger', '取消订单');
            cancel.type = 'button';
            cancel.addEventListener('click', function () {
                cancelOrder(order.orderNo, cancel);
            });
            actionsEl.appendChild(cancel);
        }
    }

    function render(order) {
        currentOrder = order;
        document.getElementById('detail-order-no').textContent = order.orderNo;
        badgeEl.className = 'status ' + window.UI.statusClass(order.status);
        badgeEl.textContent = window.UI.statusLabel(order.status);
        statusTextEl.textContent = window.UI.statusLabel(order.status);
        totalEl.textContent = '¥' + window.UI.formatMoney(order.totalAmount);
        createdAtEl.textContent = formatDate(order.createdAt);

        if (order.paidAt) {
            paidRowEl.classList.remove('hidden');
            paidAtEl.textContent = formatDate(order.paidAt);
        } else {
            paidRowEl.classList.add('hidden');
        }
        if (order.cancelledAt) {
            cancelledRowEl.classList.remove('hidden');
            cancelledAtEl.textContent = formatDate(order.cancelledAt);
        } else {
            cancelledRowEl.classList.add('hidden');
        }

        itemsEl.innerHTML = '';
        (order.items || []).forEach(function (item) {
            var row = el('div', 'item-row');
            var main = el('div');
            main.appendChild(el('div', null, item.productName));
            main.appendChild(el('div', 'item-qty', '单价 ¥' + window.UI.formatMoney(item.unitPrice)));
            row.appendChild(main);
            row.appendChild(el('div', 'item-qty', '× ' + item.quantity));
            row.appendChild(el('div', 'item-subtotal', '¥' + window.UI.formatMoney(item.subtotal)));
            itemsEl.appendChild(row);
        });

        renderActions(order);
        statusEl.classList.add('hidden');
        contentEl.classList.remove('hidden');
    }

    function cancelOrder(targetOrderNo, button) {
        if (!window.confirm('确定取消该订单吗？取消后库存会回补。')) {
            return;
        }
        button.disabled = true;
        button.textContent = '取消中...';
        window.api.post('/api/orders/' + encodeURIComponent(targetOrderNo) + '/cancel').then(function (order) {
            window.UI.showToast('订单已取消', 'success');
            showAlert('');
            render(order);
        }).catch(function (error) {
            showAlert(error.message || '取消失败');
            window.UI.showToast(error.message || '取消失败', 'error');
            button.disabled = false;
            button.textContent = '取消订单';
        });
    }

    function payOrder(targetOrderNo, button) {
        button.disabled = true;
        button.textContent = '支付中...';
        window.api.post('/api/payments', { orderNo: targetOrderNo }).then(function (payment) {
            window.UI.showToast('支付成功，支付单号：' + payment.paymentNo, 'success');
            load();
        }).catch(function (error) {
            showAlert(error.message || '支付失败');
            window.UI.showToast(error.message || '支付失败', 'error');
            button.disabled = false;
            button.textContent = '支付订单';
        });
    }

    async function load() {
        statusEl.classList.remove('hidden');
        contentEl.classList.add('hidden');
        showAlert('');
        try {
            var order = await window.api.get('/api/orders/' + encodeURIComponent(orderNo));
            render(order);
        } catch (error) {
            statusEl.classList.add('hidden');
            showAlert(error.message || '订单加载失败');
        }
    }

    load();
})();
