package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiAnswerSheetGrader
import com.example.data.model.Exam
import com.example.data.model.GradeSummary
import com.example.data.model.GradingCalculator
import com.example.data.repository.ExamRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ScanAiState {
    data object Idle : ScanAiState
    data object Scanning : ScanAiState
    data class Graded(
        val studentName: String,
        val studentCode: String,
        val examCode: String,
        val studentAnswers: List<String>,
        val summary: GradeSummary,
        val confidenceNote: String,
        val feedbackNote: String
    ) : ScanAiState
    data class Error(val message: String) : ScanAiState
}

data class ScanSheetUiState(
    val exam: Exam? = null,
    val isLoadingExam: Boolean = true,
    val examError: String? = null,
    val selectedBitmap: Bitmap? = null,
    val aiState: ScanAiState = ScanAiState.Idle,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val statusToast: String? = null,
    val isBatchMode: Boolean = false,
    val batchCount: Int = 0
)

class ScanSheetViewModel(
    private val repository: ExamRepository,
    private val aiGrader: AiAnswerSheetGrader,
    private val currentUserId: String,
    private val examId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanSheetUiState())
    val uiState: StateFlow<ScanSheetUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val result = repository.getExam(currentUserId, examId)
            result.onSuccess { exam ->
                _uiState.update {
                    it.copy(
                        exam = exam,
                        isLoadingExam = false,
                        examError = if (exam == null) "Không tìm thấy đề thi." else null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingExam = false,
                        examError = err.localizedMessage ?: "Lỗi tải thông tin đề thi."
                    )
                }
            }
        }
    }

    fun toggleBatchMode(enabled: Boolean) {
        _uiState.update { it.copy(isBatchMode = enabled) }
    }

    fun onPhotoSelected(bitmap: Bitmap, autoStartAi: Boolean = true) {
        _uiState.update {
            it.copy(
                selectedBitmap = bitmap,
                aiState = ScanAiState.Idle,
                saveError = null
            )
        }
        if (autoStartAi) {
            runAiGrading()
        }
    }

    fun runAiGrading() {
        val exam = _uiState.value.exam ?: return
        val bitmap = _uiState.value.selectedBitmap ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(aiState = ScanAiState.Scanning, saveError = null) }
            val knownCodes = exam.getAllExamCodes()
            val result = aiGrader.recognizeStudentSheet(
                bitmap = bitmap,
                totalQuestions = exam.totalQuestions.toInt(),
                knownExamCodes = knownCodes
            )
            result.onSuccess { recognized ->
                val detectedCode = recognized.examCode.ifBlank {
                    if (exam.examCodes.isNotEmpty()) exam.examCodes.keys.first() else "Chuẩn"
                }
                val key = exam.getAnswerKeyForCode(detectedCode)
                val summary = GradingCalculator.grade(
                    answerKey = key,
                    studentAnswers = recognized.answers,
                    maxScore = exam.maxScore
                )
                _uiState.update {
                    it.copy(
                        aiState = ScanAiState.Graded(
                            studentName = recognized.studentName,
                            studentCode = recognized.studentCode,
                            examCode = detectedCode,
                            studentAnswers = recognized.answers,
                            summary = summary,
                            confidenceNote = recognized.confidenceNote,
                            feedbackNote = recognized.feedbackNote
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        aiState = ScanAiState.Error(
                            err.localizedMessage ?: "Không thể nhận diện phiếu trả lời."
                        )
                    )
                }
            }
        }
    }

    fun updateStudentName(name: String) {
        _uiState.update { state ->
            val graded = state.aiState as? ScanAiState.Graded ?: return@update state
            state.copy(aiState = graded.copy(studentName = name))
        }
    }

    fun updateStudentCode(code: String) {
        _uiState.update { state ->
            val graded = state.aiState as? ScanAiState.Graded ?: return@update state
            state.copy(aiState = graded.copy(studentCode = code))
        }
    }

    fun updateExamCode(code: String) {
        val exam = _uiState.value.exam ?: return
        _uiState.update { state ->
            val graded = state.aiState as? ScanAiState.Graded ?: return@update state
            val newKey = exam.getAnswerKeyForCode(code)
            val updatedSummary = GradingCalculator.grade(
                answerKey = newKey,
                studentAnswers = graded.studentAnswers,
                maxScore = exam.maxScore
            )
            state.copy(
                aiState = graded.copy(
                    examCode = code,
                    summary = updatedSummary
                )
            )
        }
    }

    fun overrideStudentAnswer(index: Int, newOption: String) {
        val exam = _uiState.value.exam ?: return
        _uiState.update { state ->
            val graded = state.aiState as? ScanAiState.Graded ?: return@update state
            if (index !in graded.studentAnswers.indices) return@update state
            val mutable = graded.studentAnswers.toMutableList()
            mutable[index] = GradingCalculator.normalizeChoice(newOption)
            val key = exam.getAnswerKeyForCode(graded.examCode)
            val updatedSummary = GradingCalculator.grade(
                answerKey = key,
                studentAnswers = mutable,
                maxScore = exam.maxScore
            )
            state.copy(
                aiState = graded.copy(
                    studentAnswers = mutable,
                    summary = updatedSummary
                )
            )
        }
    }

    fun saveSubmission(context: Context, keepScanning: Boolean, onDone: () -> Unit) {
        val exam = _uiState.value.exam ?: return
        val graded = _uiState.value.aiState as? ScanAiState.Graded ?: return
        val bitmap = _uiState.value.selectedBitmap

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveError = null) }
            val finalName = graded.studentName.trim().ifEmpty {
                "Học sinh #${exam.submissionCount + 1}"
            }

            // Lưu ảnh bài thi vào local storage
            val tempId = System.currentTimeMillis().toString()
            val imageFileName = if (bitmap != null) {
                repository.saveSubmissionBitmap(context, bitmap, tempId)
            } else ""

            val result = repository.createSubmission(
                userId = currentUserId,
                examId = exam.id,
                studentName = finalName,
                studentCode = graded.studentCode.trim(),
                studentAnswers = graded.studentAnswers,
                correctCount = graded.summary.correctCount,
                wrongCount = graded.summary.wrongCount,
                blankCount = graded.summary.blankCount,
                score = graded.summary.score,
                notes = graded.confidenceNote,
                examCode = graded.examCode,
                imagePath = imageFileName,
                feedbackNote = graded.feedbackNote
            )

            result.onSuccess {
                if (keepScanning) {
                    _uiState.update {
                        it.copy(
                            exam = exam.copy(submissionCount = exam.submissionCount + 1),
                            selectedBitmap = null,
                            aiState = ScanAiState.Idle,
                            isSaving = false,
                            batchCount = it.batchCount + 1,
                            statusToast = "Đã lưu điểm ${graded.summary.score}đ cho $finalName! Sẵn sàng quét bài tiếp theo."
                        )
                    }
                } else {
                    _uiState.update { it.copy(isSaving = false) }
                    onDone()
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveError = err.localizedMessage ?: "Không thể lưu kết quả chấm."
                    )
                }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(statusToast = null) }
    }
}
