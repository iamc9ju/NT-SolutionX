const MasterRegisterTab = {
    template: `
        <div class="space-y-6 fade-in max-w-2xl mx-auto">
            <div class="bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-2xl p-6 shadow-soft dark:shadow-none space-y-6">
                
                <!-- Tab Header -->
                <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-neutral-100 dark:border-neutral-800">
                    <div class="flex items-center gap-2">
                        <i class="fa-solid fa-file-excel text-emerald-500 text-sm"></i>
                        <h3 class="text-sm font-bold text-neutral-900 dark:text-white uppercase tracking-wider">
                            นำเข้าเลขหมายด้วยไฟล์ Excel (Excel Batch Import)
                        </h3>
                    </div>
                    <!-- Template Download Link -->
                    <button @click="downloadTemplate" class="inline-flex items-center gap-1.5 px-3 py-1.5 bg-emerald-50 hover:bg-emerald-100 dark:bg-emerald-950/20 dark:hover:bg-emerald-900/30 border border-emerald-250 dark:border-emerald-900/40 rounded-xl text-emerald-700 dark:text-emerald-400 font-bold text-[11px] transition-all cursor-pointer">
                        <i class="fa-solid fa-download"></i>
                        ดาวน์โหลดเทมเพลต (Template)
                    </button>
                </div>

                <!-- Alert Messages -->
                <div v-if="success" class="bg-emerald-50 dark:bg-emerald-950/20 border border-emerald-200 dark:border-emerald-900/40 rounded-xl p-4 flex items-start gap-3">
                    <i class="fa-solid fa-circle-check text-emerald-600 dark:text-emerald-400 text-base mt-0.5 animate-bounce"></i>
                    <div class="space-y-1">
                        <p class="text-xs font-bold text-emerald-900 dark:text-emerald-400">นำเข้าสำเร็จ!</p>
                        <p class="text-[11px] text-emerald-700 dark:text-emerald-500 leading-relaxed">{{ successMessage }}</p>
                    </div>
                </div>

                <div v-if="error" class="bg-red-50 dark:bg-red-950/20 border border-red-200 dark:border-red-900/40 rounded-xl p-4 flex items-start gap-3">
                    <i class="fa-solid fa-circle-exclamation text-red-600 dark:text-red-400 text-base mt-0.5"></i>
                    <div class="space-y-1 w-full">
                        <p class="text-xs font-bold text-red-900 dark:text-red-400">เกิดข้อผิดพลาดในการนำเข้าข้อมูล</p>
                        <p class="text-[11px] text-red-750 dark:text-red-400 leading-relaxed mb-2">{{ error }}</p>
                        
                        <!-- Detailed Row-by-Row Errors -->
                        <div v-if="errorDetails.length > 0" class="max-h-40 overflow-y-auto border border-red-200/50 dark:border-red-900/20 rounded-lg p-2 bg-red-100/50 dark:bg-red-955/30 text-[10px] space-y-1 scrollbar-thin">
                            <div v-for="(detail, idx) in errorDetails" :key="idx" class="text-red-700 dark:text-red-400 font-mono flex gap-1.5 items-start">
                                <span class="select-none text-red-400">•</span>
                                <span>{{ detail }}</span>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- Drag-and-Drop Area -->
                <div class="space-y-4">
                    <!-- Instruction Card -->
                    <div class="bg-neutral-50 dark:bg-neutral-955 p-4 rounded-xl border border-neutral-150 dark:border-neutral-900 space-y-2 text-xs">
                        <p class="font-bold text-neutral-800 dark:text-neutral-200">คำแนะนำในการจัดเตรียมไฟล์ Excel:</p>
                        <ul class="list-disc pl-4 space-y-1 text-neutral-650 dark:text-neutral-400 text-[11px] leading-relaxed">
                            <li>กรอกข้อมูล <strong>MSISDN</strong> (9 หรือ 10 หลัก), <strong>ICCID</strong> (19 หรือ 20 หลัก), และ <strong>IMSI</strong> (15 หลัก) เป็นตัวเลขเท่านั้น</li>
                            <li>ช่องประเภทบริการ (Service Type) สามารถเป็น <code>Prepaid</code> หรือ <code>Postpaid</code> (หากเว้นว่างไว้ ระบบจะไม่บันทึกค่าประเภทบริการ)</li>
                            <li>ห้ามลบแถวหัวตาราง (แถวที่ 1) และแถวข้อมูลต้องไม่มีการซ้ำซ้อนกันในไฟล์</li>
                        </ul>
                    </div>

                    <!-- Drop Zone -->
                    <div 
                        @dragover.prevent="dragOver = true"
                        @dragleave.prevent="dragOver = false"
                        @drop.prevent="handleFileDrop"
                        @click="triggerFileSelect"
                        :class="[
                            'border-2 border-dashed rounded-2xl p-8 text-center transition-all cursor-pointer flex flex-col items-center justify-center gap-3',
                            dragOver ? 'border-emerald-500 bg-emerald-50/35 dark:bg-emerald-950/10' : 'border-neutral-300 dark:border-neutral-800 hover:border-neutral-450 dark:hover:border-neutral-700 bg-neutral-50/30 dark:bg-neutral-900/10'
                        ]"
                    >
                        <input type="file" ref="fileInput" @change="handleFileSelect" accept=".xlsx, .xls" class="hidden">
                        
                        <div class="w-12 h-12 rounded-full bg-emerald-50 dark:bg-emerald-950/30 flex items-center justify-center text-emerald-600 dark:text-emerald-400 text-lg">
                            <i class="fa-solid fa-cloud-arrow-up animate-pulse"></i>
                        </div>
                        
                        <div class="space-y-1">
                            <p class="text-xs font-bold text-neutral-850 dark:text-neutral-200">
                                ลากไฟล์ Excel มาวางที่นี่ หรือ <span class="text-emerald-600 dark:text-emerald-400 font-extrabold hover:underline">คลิกเพื่อเลือกไฟล์</span>
                            </p>
                            <p class="text-[10px] text-neutral-400 dark:text-neutral-550">
                                รองรับเฉพาะไฟล์นามสกุล .xlsx และ .xls ขนาดไม่เกิน 10MB
                            </p>
                        </div>
                    </div>

                    <!-- Selected File Info -->
                    <div v-if="selectedFile" class="bg-neutral-50 dark:bg-neutral-955 border border-neutral-200 dark:border-neutral-850 rounded-xl p-3.5 flex items-center justify-between gap-3 text-xs">
                        <div class="flex items-center gap-3 min-w-0">
                            <div class="w-9 h-9 bg-emerald-50 dark:bg-emerald-950/20 text-emerald-600 dark:text-emerald-400 rounded-lg flex items-center justify-center text-base shrink-0">
                                <i class="fa-solid fa-file-invoice"></i>
                            </div>
                            <div class="min-w-0">
                                <p class="font-bold text-neutral-800 dark:text-neutral-200 truncate">{{ selectedFile.name }}</p>
                                <p class="text-[10px] text-neutral-400 dark:text-neutral-500 font-mono">{{ formatFileSize(selectedFile.size) }}</p>
                            </div>
                        </div>
                        <button @click="removeFile" class="w-8 h-8 rounded-lg hover:bg-neutral-100 dark:hover:bg-neutral-800 text-neutral-400 dark:text-neutral-550 hover:text-red-500 dark:hover:text-red-400 transition-all cursor-pointer flex items-center justify-center">
                            <i class="fa-solid fa-trash-can"></i>
                        </button>
                    </div>
                </div>

                <!-- Submit/Actions Footer -->
                <div class="flex justify-end gap-2 pt-4 border-t border-neutral-100 dark:border-neutral-800">
                    <button @click="resetState" :disabled="submitting" class="px-4 py-2 border border-neutral-200 dark:border-neutral-800 hover:bg-neutral-50 dark:hover:bg-neutral-800 rounded-xl font-bold text-xs text-neutral-700 dark:text-neutral-350 transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed">
                        ล้างค่า (Reset)
                    </button>
                    <button @click="uploadAndImport" :disabled="submitting || !selectedFile" class="px-4 py-2 bg-neutral-900 dark:bg-neutral-100 hover:bg-neutral-800 dark:hover:bg-neutral-200 text-white dark:text-neutral-950 rounded-xl font-bold text-xs shadow-sm transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5">
                        <i v-if="submitting" class="fa-solid fa-spinner animate-spin"></i>
                        <i v-else class="fa-solid fa-file-import"></i>
                        นำเข้าข้อมูล (Import)
                    </button>
                </div>

            </div>
        </div>
    `,
    emits: ['registered'],
    setup(props, { emit }) {
        const selectedFile = Vue.ref(null);
        const dragOver = Vue.ref(false);
        const submitting = Vue.ref(false);
        const error = Vue.ref('');
        const errorDetails = Vue.ref([]);
        const success = Vue.ref(false);
        const successMessage = Vue.ref('');
        const fileInput = Vue.ref(null);

        // Get context path helper
        const getContextPath = () => {
            const path = window.location.pathname;
            const segments = path.split('/');
            if (segments.length > 1 && segments[1] && !segments[1].endsWith('.html') && segments[1] !== 'index.htm' && segments[1] !== 'index.jsp') {
                return '/' + segments[1];
            }
            return '';
        };

        const downloadTemplate = () => {
            const contextPath = getContextPath();
            window.location.href = `${contextPath}/api/master/template`;
        };

        const triggerFileSelect = () => {
            if (fileInput.value) {
                fileInput.value.click();
            }
        };

        const handleFileSelect = (e) => {
            const files = e.target.files;
            if (files.length > 0) {
                validateAndSetFile(files[0]);
            }
        };

        const handleFileDrop = (e) => {
            dragOver.value = false;
            const files = e.dataTransfer.files;
            if (files.length > 0) {
                validateAndSetFile(files[0]);
            }
        };

        const validateAndSetFile = (file) => {
            error.value = '';
            errorDetails.value = [];
            success.value = false;

            const name = file.name.toLowerCase();
            if (!name.endsWith('.xlsx') && !name.endsWith('.xls')) {
                error.value = 'ประเภทไฟล์ไม่ถูกต้อง กรุณาเลือกเฉพาะไฟล์ Excel (.xlsx หรือ .xls) เท่านั้น';
                selectedFile.value = null;
                return;
            }

            if (file.size > 10 * 1024 * 1024) {
                error.value = 'ขนาดไฟล์ใหญ่เกินไป จำกัดขนาดไฟล์ไม่เกิน 10MB';
                selectedFile.value = null;
                return;
            }

            selectedFile.value = file;
        };

        const removeFile = () => {
            selectedFile.value = null;
            if (fileInput.value) {
                fileInput.value.value = '';
            }
        };

        const resetState = () => {
            removeFile();
            error.value = '';
            errorDetails.value = [];
            success.value = false;
            successMessage.value = '';
        };

        const formatFileSize = (bytes) => {
            if (bytes === 0) return '0 Bytes';
            const k = 1024;
            const sizes = ['Bytes', 'KB', 'MB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        };

        const uploadAndImport = async () => {
            if (!selectedFile.value) return;

            submitting.value = true;
            error.value = '';
            errorDetails.value = [];
            success.value = false;

            try {
                const contextPath = getContextPath();
                
                // Read file as ArrayBuffer and send as raw binary stream
                const arrayBuffer = await selectedFile.value.arrayBuffer();

                const res = await fetch(`${contextPath}/api/master/import`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
                    },
                    body: arrayBuffer
                });

                const resData = await res.json();

                if (res.ok) {
                    success.value = true;
                    successMessage.value = resData.message || 'นำเข้าข้อมูลเรียบร้อยแล้ว';
                    removeFile();
                    emit('registered');
                } else {
                    error.value = resData.error || 'การนำเข้าข้อมูลล้มเหลว';
                    if (resData.details && Array.isArray(resData.details)) {
                        errorDetails.value = resData.details;
                    }
                }
            } catch (err) {
                console.error('Import error:', err);
                error.value = 'ไม่สามารถนำเข้าข้อมูลได้เนื่องจากปัญหาการเชื่อมต่อเครือข่าย';
            } finally {
                submitting.value = false;
            }
        };

        return {
            selectedFile,
            dragOver,
            submitting,
            error,
            errorDetails,
            success,
            successMessage,
            fileInput,
            downloadTemplate,
            triggerFileSelect,
            handleFileSelect,
            handleFileDrop,
            removeFile,
            resetState,
            formatFileSize,
            uploadAndImport
        };
    }
};
